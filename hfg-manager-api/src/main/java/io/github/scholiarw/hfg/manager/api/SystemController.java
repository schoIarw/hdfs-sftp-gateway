package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {
  private final JdbcClient db;
  private final HdfsBundleService bundles;
  private final GatewayCertificateService certificates;
  private final DatabaseDialect dialect;

  SystemController(
      JdbcClient db, HdfsBundleService bundles, GatewayCertificateService certificates, DatabaseDialect dialect) {
    this.db = db;
    this.bundles = bundles;
    this.certificates = certificates;
    this.dialect = dialect;
  }

  @PostMapping(value = "/hdfs-clusters/import", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> importCluster(
      @RequestParam String id, @RequestParam String name, @RequestPart("file") MultipartFile file)
      throws java.io.IOException {
    return bundles.install(id, name, file);
  }

  @GetMapping("/hdfs-clusters")
  List<Map<String, Object>> clusters() {
    return db.sql("select * from hdfs_cluster order by name").query().listOfRows();
  }

  @GetMapping("/service-groups")
  List<Map<String, Object>> groups() {
    return db.sql("select * from service_group order by name").query().listOfRows();
  }

  @PostMapping("/service-groups")
  @ResponseStatus(HttpStatus.CREATED)
  void createGroup(@Valid @RequestBody ServiceGroup r) {
    Instant n = Instant.now();
    db.sql(
            "insert into service_group(id,name,vip,hdfs_cluster_id,status,created_at,updated_at) values(:id,:name,:vip,:hdfs,'ENABLED',:n,:n)")
        .param("id", r.id())
        .param("name", r.name())
        .param("vip", r.vip())
        .param("hdfs", r.hdfsClusterId())
        .param("n", n)
        .update();
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

  @PutMapping("/gateways/{id}/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void heartbeat(@PathVariable String id, @Valid @RequestBody GatewayHeartbeat r) {
    Instant n = Instant.now();
    db.sql(dialect.choose(
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
        .param("n", n)
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
