package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SystemController.class);
  private final JdbcClient db;
  private final HdfsBundleService bundles;
  private final GatewayCertificateService certificates;
  private final DatabaseDialect dialect;

  SystemController(
      JdbcClient db,
      HdfsBundleService bundles,
      GatewayCertificateService certificates,
      DatabaseDialect dialect) {
    this.db = db;
    this.bundles = bundles;
    this.certificates = certificates;
    this.dialect = dialect;
  }

  @PostMapping(value = "/hdfs-clusters/import", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> importCluster(
      @RequestParam String id, @RequestParam String name, @RequestPart("file") MultipartFile file) {
    return bundles.install(id, name, file);
  }

  @PutMapping(value = "/hdfs-clusters/{id}/authentication", consumes = "multipart/form-data")
  Map<String, Object> reuploadAuthentication(
      @PathVariable String id, @RequestPart("file") MultipartFile file) {
    return bundles.reinstallAuthentication(id, file);
  }

  @DeleteMapping("/hdfs-clusters/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteCluster(@PathVariable String id) {
    bundles.delete(id);
  }

  @GetMapping("/hdfs-clusters")
  List<Map<String, Object>> clusters() {
    return bundles.list();
  }

  @GetMapping("/service-groups")
  List<Map<String, Object>> groups() {
    return db.sql("select * from service_group order by name").query().listOfRows();
  }

  @PostMapping("/service-groups")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> createGroup(@Valid @RequestBody ServiceGroup r) {
    Integer clusters =
        db.sql("select count(*) from hdfs_cluster where id=:id")
            .param("id", r.hdfsClusterId())
            .query(Integer.class)
            .single();
    if (clusters == null || clusters == 0)
      throw new NoSuchElementException("HDFS 连接 “" + r.hdfsClusterId() + "” 不存在，请先新建该连接");
    Instant n = Instant.now();
    db.sql(
            "insert into service_group(id,name,vip,hdfs_cluster_id,status,created_at,updated_at) values(:id,:name,:vip,:hdfs,'ENABLED',:n,:n)")
        .param("id", r.id())
        .param("name", r.name())
        .param("vip", r.vip())
        .param("hdfs", r.hdfsClusterId())
        .param("n", java.sql.Timestamp.from(n))
        .update();
    return db
        .sql("select * from service_group where id=:id")
        .param("id", r.id())
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("服务组已创建但无法读取：" + r.id()));
  }

  @DeleteMapping("/service-groups/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void deleteGroup(@PathVariable String id) {
    Map<String, Object> group =
        db
            .sql("select id from service_group where id=:id")
            .param("id", id)
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("服务组 “" + id + "” 不存在"));
    List<String> users =
        db.sql("select username from ftp_user where service_group_id=:id order by username")
            .param("id", id)
            .query(String.class)
            .list();
    // 只看这个服务组自己的用户所拥有的目录；同一 HDFS 连接上其它服务组的目录与本组无关。
    List<String> directories =
        db.sql(
                "select d.name from directory_mapping d join ftp_user u on u.id=d.owner_user_id"
                    + " where u.service_group_id=:id order by d.name")
            .param("id", id)
            .query(String.class)
            .list();
    List<String> online =
        db.sql(
                "select hostname from gateway_node where service_group_id=:id and status='UP' and last_heartbeat_at>:cutoff order by hostname")
            .param("id", id)
            .param("cutoff", java.sql.Timestamp.from(GatewayPresence.cutoff()))
            .query(String.class)
            .list();
    if (!users.isEmpty() || !directories.isEmpty() || !online.isEmpty())
      throw new IllegalStateException(blockedGroupMessage(id, users, directories, online));

    // Offline or failed gateways, their certificates and published snapshots belong to this group
    // and cannot authenticate anywhere else, so they are removed with it.
    int nodes =
        db.sql("delete from gateway_node where service_group_id=:id").param("id", id).update();
    int certificates =
        db.sql("delete from gateway_certificate where service_group_id=:id")
            .param("id", id)
            .update();
    int snapshots =
        db.sql("delete from config_snapshot where service_group_id=:id").param("id", id).update();
    if (db.sql("delete from service_group where id=:id").param("id", id).update() == 0)
      throw new NoSuchElementException("服务组 “" + id + "” 不存在");
    log.info(
        "Deleted service group {} with {} offline gateway node(s), {} certificate(s), {} snapshot(s)",
        id,
        nodes,
        certificates,
        snapshots);
  }

  private static String blockedGroupMessage(
      String id, List<String> users, List<String> directories, List<String> online) {
    List<String> reasons = new ArrayList<>();
    if (!users.isEmpty())
      reasons.add("用户 " + String.join("、", users) + "（共 " + users.size() + " 个）");
    if (!directories.isEmpty())
      reasons.add("该组用户归属的目录映射 " + String.join("、", directories) + "（请在“目录管理”中先删除或转移这些目录）");
    if (!online.isEmpty()) reasons.add("在线 Gateway 节点 " + String.join("、", online) + "（请先停止这些网关）");
    return "服务组 “"
        + id
        + "” 仍有关联，无法删除："
        + String.join("；", reasons)
        + "。离线或异常的 Gateway 节点不影响删除，会随服务组一起清理。";
  }

  @GetMapping("/gateways")
  List<Map<String, Object>> gateways() {
    return db.sql("select * from gateway_node order by service_group_id,hostname")
        .query()
        .listOfRows();
  }

  @PostMapping(value = "/gateway-certificates", produces = "application/zip")
  ResponseEntity<byte[]> gatewayCertificate(
      @Valid @RequestBody GatewayCertificateRequest request, Principal principal) throws Exception {
    var generated =
        certificates.generate(request.gatewayId(), request.serviceGroupId(), principal.getName());
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
        .header(
            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + request.gatewayId() + "-certificate.zip\"")
        .header("X-HFG-Certificate-Fingerprint", generated.fingerprint())
        .header("X-HFG-Certificate-Expires", generated.notAfter().toString())
        .body(generated.zip());
  }

  @GetMapping("/gateway-certificates")
  List<Map<String, Object>> gatewayCertificates() {
    return certificates.inventory();
  }

  @GetMapping(value = "/gateway-certificates/{id}/download", produces = "application/zip")
  ResponseEntity<byte[]> downloadGatewayCertificate(@PathVariable UUID id) throws Exception {
    var archive = certificates.download(id);
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
        .header(
            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + archive.gatewayId() + "-certificate.zip\"")
        .header("X-HFG-Certificate-Fingerprint", archive.fingerprint())
        .body(archive.zip());
  }

  @DeleteMapping("/gateway-certificates/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteGatewayCertificate(@PathVariable UUID id) {
    certificates.delete(id);
  }

  @PutMapping("/gateways/{id}/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void heartbeat(@PathVariable String id, @Valid @RequestBody GatewayHeartbeat r) {
    Instant n = Instant.now();
    db.sql(
            dialect.choose(
                "insert into gateway_node(id,service_group_id,hostname,role,management_address,ip_address,ftp_port,sftp_port,management_port,software_version,snapshot_version,last_heartbeat_at,status,created_at,updated_at) values(:id,:g,:h,:role,:addr,:ip,:ftp,:sftp,:management,:version,:snapshot,:n,'UP',:n,:n) on conflict(id) do update set role=excluded.role,management_address=excluded.management_address,ip_address=excluded.ip_address,ftp_port=excluded.ftp_port,sftp_port=excluded.sftp_port,management_port=excluded.management_port,software_version=excluded.software_version,snapshot_version=excluded.snapshot_version,last_heartbeat_at=excluded.last_heartbeat_at,status='UP',updated_at=excluded.updated_at",
                "insert into gateway_node(id,service_group_id,hostname,role,management_address,ip_address,ftp_port,sftp_port,management_port,software_version,snapshot_version,last_heartbeat_at,status,created_at,updated_at) values(:id,:g,:h,:role,:addr,:ip,:ftp,:sftp,:management,:version,:snapshot,:n,'UP',:n,:n) on duplicate key update role=values(role),management_address=values(management_address),ip_address=values(ip_address),ftp_port=values(ftp_port),sftp_port=values(sftp_port),management_port=values(management_port),software_version=values(software_version),snapshot_version=values(snapshot_version),last_heartbeat_at=values(last_heartbeat_at),status='UP',updated_at=values(updated_at)"))
        .param("id", id)
        .param("g", r.serviceGroupId())
        .param("h", r.hostname())
        .param("role", r.role())
        .param("addr", r.managementAddress())
        .param("ip", r.ipAddress())
        .param("ftp", r.ftpPort())
        .param("sftp", r.sftpPort())
        .param("management", r.managementPort())
        .param("version", r.softwareVersion())
        .param("snapshot", r.snapshotVersion())
        .param("n", java.sql.Timestamp.from(n))
        .update();
  }

  record ServiceGroup(
      @NotBlank String id,
      @NotBlank String name,
      @NotBlank String vip,
      @NotBlank String hdfsClusterId) {}

  record GatewayCertificateRequest(@NotBlank String gatewayId, @NotBlank String serviceGroupId) {}

  record GatewayHeartbeat(
      @NotBlank String serviceGroupId,
      @NotBlank String hostname,
      @NotBlank String role,
      @NotBlank String managementAddress,
      @NotBlank String ipAddress,
      @Min(1) @Max(65535) int ftpPort,
      @Min(1) @Max(65535) int sftpPort,
      @Min(1) @Max(65535) int managementPort,
      String softwareVersion,
      long snapshotVersion) {}
}
