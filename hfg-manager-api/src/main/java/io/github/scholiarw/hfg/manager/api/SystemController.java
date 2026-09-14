package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {
  private final JdbcClient db;

  SystemController(JdbcClient db) {
    this.db = db;
  }

  @GetMapping("/hdfs-clusters")
  List<Map<String, Object>> clusters() {
    return db.sql("select * from hdfs_cluster order by name").query().listOfRows();
  }

  @PostMapping("/hdfs-clusters")
  @ResponseStatus(HttpStatus.CREATED)
  void createCluster(@Valid @RequestBody HdfsCluster r) {
    Instant n = Instant.now();
    db.sql(
            "insert into hdfs_cluster(id,name,default_fs,nameservice,kerberos_enabled,principal,keytab_secret_ref,config_resource_refs,status,created_at,updated_at) values(:id,:name,:fs,:ns,:k,:p,:key,:resources,'ENABLED',:n,:n)")
        .param("id", r.id())
        .param("name", r.name())
        .param("fs", r.defaultFs())
        .param("ns", r.nameservice())
        .param("k", r.kerberosEnabled())
        .param("p", r.principal())
        .param("key", r.keytabSecretRef())
        .param("resources", r.configResourceRefs())
        .param("n", n)
        .update();
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

  @PutMapping("/gateways/{id}/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void heartbeat(@PathVariable String id, @Valid @RequestBody GatewayHeartbeat r) {
    Instant n = Instant.now();
    db.sql(
            "insert into gateway_node(id,service_group_id,hostname,role,management_address,software_version,snapshot_version,last_heartbeat_at,status,created_at,updated_at) values(:id,:g,:h,:role,:addr,:version,:snapshot,:n,'UP',:n,:n) on conflict(id) do update set role=excluded.role,management_address=excluded.management_address,software_version=excluded.software_version,snapshot_version=excluded.snapshot_version,last_heartbeat_at=excluded.last_heartbeat_at,status='UP',updated_at=excluded.updated_at")
        .param("id", id)
        .param("g", r.serviceGroupId())
        .param("h", r.hostname())
        .param("role", r.role())
        .param("addr", r.managementAddress())
        .param("version", r.softwareVersion())
        .param("snapshot", r.snapshotVersion())
        .param("n", n)
        .update();
  }

  record HdfsCluster(
      @NotBlank String id,
      @NotBlank String name,
      @NotBlank String defaultFs,
      String nameservice,
      boolean kerberosEnabled,
      String principal,
      String keytabSecretRef,
      String configResourceRefs) {}

  record ServiceGroup(
      @NotBlank String id,
      @NotBlank String name,
      @NotBlank String vip,
      @NotBlank String hdfsClusterId) {}

  record GatewayHeartbeat(
      @NotBlank String serviceGroupId,
      @NotBlank String hostname,
      @NotBlank String role,
      @NotBlank String managementAddress,
      String softwareVersion,
      long snapshotVersion) {}
}
