package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/directories")
class DirectoryController {
  private final JdbcClient db;
  private final DirectoryProvisioningService provisioner;
  private final DatabaseDialect dialect;

  DirectoryController(
      JdbcClient db, DirectoryProvisioningService provisioner, DatabaseDialect dialect) {
    this.db = db;
    this.provisioner = provisioner;
    this.dialect = dialect;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return db.sql(
            dialect.choose(
                "select d.*,coalesce(string_agg(u.username||':'||g.access_mode,',' order by u.username),'') as user_bindings from directory_mapping d left join directory_grant g on g.directory_mapping_id=d.id left join ftp_user u on u.id=g.user_id group by d.id order by d.name",
                "select d.*,coalesce(group_concat(concat(u.username,':',g.access_mode) order by u.username separator ','),'') as user_bindings from directory_mapping d left join directory_grant g on g.directory_mapping_id=d.id left join ftp_user u on u.id=g.user_id group by d.id order by d.name"))
        .query()
        .listOfRows();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  Map<String, Object> create(@Valid @RequestBody DirectoryRequest r) {
    ClusterBindingGuard.requireUserCluster(db, r.userId(), r.hdfsClusterId());
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    db.sql(
            "insert into directory_mapping(id,name,virtual_path,hdfs_path,hdfs_cluster_id,auto_create,namespace_quota,space_quota_bytes,status,provisioning_status,created_at,updated_at) values(:id,:name,:virtual,:hdfs,:cluster,:auto,:nq,:sq,'ENABLED',:ps,:now,:now)")
        .param("id", id)
        .param("name", r.name())
        .param("virtual", r.virtualPath())
        .param("hdfs", r.hdfsPath())
        .param("cluster", r.hdfsClusterId())
        .param("auto", r.autoCreate())
        .param("nq", r.namespaceQuota())
        .param("sq", r.spaceQuotaBytes())
        .param("ps", r.autoCreate() ? "PENDING" : "MANUAL")
        .param("now", java.sql.Timestamp.from(now))
        .update();
    db.sql(
            "insert into directory_grant(id,user_id,directory_mapping_id,access_mode,created_at) values(:grant,:user,:directory,:mode,:now)")
        .param("grant", UUID.randomUUID())
        .param("user", r.userId())
        .param("directory", id)
        .param("mode", r.accessMode())
        .param("now", java.sql.Timestamp.from(now))
        .update();
    if (r.autoCreate())
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                public void afterCommit() {
                  try {
                    provisioner.provision(id);
                  } catch (Exception ignored) {
                  }
                }
              });
    return get(id);
  }

  @PostMapping("/{id}/provision")
  Map<String, Object> provision(@PathVariable UUID id) {
    provisioner.provision(id);
    return get(id);
  }

  @GetMapping("/{id}")
  Map<String, Object> get(@PathVariable UUID id) {
    return db.sql("select * from directory_mapping where id=:id")
        .param("id", id)
        .query()
        .singleRow();
  }

  /** Removes the directory mapping; its user grants are removed by the database cascade. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void delete(@PathVariable UUID id) {
    db.sql("delete from directory_mapping where id=:id").param("id", id).update();
  }

  record DirectoryRequest(
      @NotBlank String name,
      @NotBlank @Pattern(regexp = "/.*") String virtualPath,
      @NotBlank @Pattern(regexp = "/.*") String hdfsPath,
      @NotBlank String hdfsClusterId,
      boolean autoCreate,
      @Min(-1) long namespaceQuota,
      @Min(-1) long spaceQuotaBytes,
      @NotNull UUID userId,
      @NotBlank @Pattern(regexp = "READ_ONLY|READ_WRITE") String accessMode) {}
}
