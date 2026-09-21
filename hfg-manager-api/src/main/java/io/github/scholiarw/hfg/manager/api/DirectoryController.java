package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/directories")
class DirectoryController {
  private final JdbcClient db;
  private final DatabaseDialect dialect;
  private final DirectoryProvisioningService provisioner;

  DirectoryController(
      JdbcClient db, DatabaseDialect dialect, DirectoryProvisioningService provisioner) {
    this.db = db;
    this.dialect = dialect;
    this.provisioner = provisioner;
  }

  @GetMapping
  List<Map<String, Object>> list(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) UUID userId) {
    String normalized = query == null ? "" : query.trim().toLowerCase();
    StringBuilder sql =
        new StringBuilder(
            "select d.*,u.username owner_username from directory_mapping d left join ftp_user u on u.id=d.owner_user_id where 1=1");
    if (!normalized.isEmpty())
      sql.append(
          " and (lower(d.name) like :query or lower(d.virtual_path) like :query or lower(d.hdfs_path) like :query or lower(coalesce(u.username,'')) like :query)");
    if (userId != null) sql.append(" and d.owner_user_id=:user");
    sql.append(" order by u.username,d.virtual_path,d.name");
    JdbcClient.StatementSpec statement = db.sql(sql.toString());
    if (!normalized.isEmpty()) statement = statement.param("query", "%" + normalized + "%");
    if (userId != null) statement = statement.param("user", dialect.id(userId));
    return statement.query().listOfRows();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  Map<String, Object> create(@Valid @RequestBody DirectoryRequest request) {
    ClusterBindingGuard.requireUserCluster(db, dialect, request.userId(), request.hdfsClusterId());
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    db.sql(
            "insert into directory_mapping(id,name,virtual_path,hdfs_path,hdfs_cluster_id,auto_create,namespace_quota,space_quota_bytes,status,provisioning_status,owner_user_id,access_mode,created_at,updated_at) values(:id,:name,:virtual,:hdfs,:cluster,:auto,:files,:space,'ENABLED',:provisioning,:user,:mode,:now,:now)")
        .param("id", dialect.id(id))
        .param("name", request.name())
        .param("virtual", request.virtualPath())
        .param("hdfs", request.hdfsPath())
        .param("cluster", request.hdfsClusterId())
        .param("auto", request.autoCreate())
        .param("files", request.fileQuota())
        .param("space", request.spaceQuotaBytes())
        .param("provisioning", request.autoCreate() ? "PENDING" : "MANUAL")
        .param("user", dialect.id(request.userId()))
        .param("mode", request.accessMode())
        .param("now", java.sql.Timestamp.from(now))
        .update();
    scheduleProvision(id, request.autoCreate());
    return get(id);
  }

  @PutMapping("/{id}")
  @Transactional
  Map<String, Object> update(
      @PathVariable UUID id, @Valid @RequestBody DirectoryRequest request) {
    requireDirectory(id);
    ClusterBindingGuard.requireUserCluster(db, dialect, request.userId(), request.hdfsClusterId());
    int changed =
        db.sql(
                "update directory_mapping set name=:name,virtual_path=:virtual,hdfs_path=:hdfs,hdfs_cluster_id=:cluster,auto_create=:auto,namespace_quota=:files,space_quota_bytes=:space,owner_user_id=:user,access_mode=:mode,provisioning_status=:provisioning,provisioning_error=null,updated_at=:now where id=:id")
            .param("name", request.name())
            .param("virtual", request.virtualPath())
            .param("hdfs", request.hdfsPath())
            .param("cluster", request.hdfsClusterId())
            .param("auto", request.autoCreate())
            .param("files", request.fileQuota())
            .param("space", request.spaceQuotaBytes())
            .param("user", dialect.id(request.userId()))
            .param("mode", request.accessMode())
            .param("provisioning", request.autoCreate() ? "PENDING" : "MANUAL")
            .param("now", java.sql.Timestamp.from(Instant.now()))
            .param("id", dialect.id(id))
            .update();
    if (changed == 0) throw new java.util.NoSuchElementException("目录不存在");
    scheduleProvision(id, request.autoCreate());
    return get(id);
  }

  @PostMapping("/{id}/provision")
  Map<String, Object> provision(@PathVariable UUID id) {
    provisioner.provision(id);
    return get(id);
  }

  @GetMapping("/{id}")
  Map<String, Object> get(@PathVariable UUID id) {
    return db.sql(
            "select d.*,u.username owner_username from directory_mapping d left join ftp_user u on u.id=d.owner_user_id where d.id=:id")
        .param("id", dialect.id(id))
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElseThrow(() -> new java.util.NoSuchElementException("目录不存在"));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void delete(@PathVariable UUID id) {
    requireDirectory(id);
    db.sql("delete from directory_mapping where id=:id")
        .param("id", dialect.id(id))
        .update();
  }

  private void requireDirectory(UUID id) {
    if (db.sql("select count(*) from directory_mapping where id=:id")
            .param("id", dialect.id(id))
            .query(Long.class)
            .single()
        == 0) throw new java.util.NoSuchElementException("目录不存在");
  }

  private void scheduleProvision(UUID id, boolean enabled) {
    if (!enabled) return;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              provisioner.provision(id);
            } catch (RuntimeException ignored) {
              // The provisioning service persists FAILED and its reason for operators.
            }
          }
        });
  }

  record DirectoryRequest(
      @NotBlank String name,
      @NotBlank @Pattern(regexp = "/.*") String virtualPath,
      @NotBlank @Pattern(regexp = "/.*") String hdfsPath,
      @NotBlank String hdfsClusterId,
      boolean autoCreate,
      @Min(-1) long fileQuota,
      @Min(-1) long spaceQuotaBytes,
      @NotNull UUID userId,
      @NotBlank @Pattern(regexp = "READ_ONLY|READ_WRITE") String accessMode) {}
}
