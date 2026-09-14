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

  DirectoryController(JdbcClient db, DirectoryProvisioningService provisioner) {
    this.db = db;
    this.provisioner = provisioner;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return db.sql("select * from directory_mapping order by name").query().listOfRows();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  Map<String, Object> create(@Valid @RequestBody DirectoryRequest r) {
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
        .param("now", now)
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

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
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
      @Min(-1) long spaceQuotaBytes) {}
}
