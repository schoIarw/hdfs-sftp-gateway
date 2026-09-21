package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.storage.hdfs.HdfsStorageClientFactory;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class DirectoryProvisioningService {
  private static final Logger log = LoggerFactory.getLogger(DirectoryProvisioningService.class);
  private final JdbcClient db;
  private final DatabaseDialect dialect;

  DirectoryProvisioningService(JdbcClient db, DatabaseDialect dialect) {
    this.db = db;
    this.dialect = dialect;
  }

  @Scheduled(fixedDelayString = "${hfg.directory-provisioning.interval:PT30S}")
  void retry() {
    for (Map<String, Object> row :
        db.sql(
                "select d.id from directory_mapping d where d.auto_create=true and d.provisioning_status in('PENDING','FAILED') order by d.created_at limit 20")
            .query()
            .listOfRows()) {
      UUID id = UUID.fromString(String.valueOf(row.get("id")));
      try {
        provision(id);
      } catch (Exception e) {
        log.warn("Directory provisioning retry failed for {}", id, e);
      }
    }
  }

  void provision(UUID id) {
    Map<String, Object> r =
        db.sql(
                "select d.*,h.default_fs,h.kerberos_enabled,h.principal,h.keytab_secret_ref,h.config_resource_refs from directory_mapping d join hdfs_cluster h on h.id=d.hdfs_cluster_id where d.id=:id")
            .param("id", dialect.id(id))
            .query()
            .singleRow();
    try {
      String keytab = secretPath((String) r.get("keytab_secret_ref"));
      List<String> resources = parseResources((String) r.get("config_resource_refs"));
      var factory =
          new HdfsStorageClientFactory(
              new HdfsStorageClientFactory.Settings(
                  (String) r.get("default_fs"),
                  resources,
                  (Boolean.TRUE.equals(r.get("kerberos_enabled"))
                      ? (String) r.get("principal")
                      : null),
                  keytab,
                  false));
      try (var storage = factory.forEffectiveUser(null)) {
        String path = (String) r.get("hdfs_path");
        storage.mkdirs(path);
        long nq = ((Number) r.get("namespace_quota")).longValue(),
            sq = ((Number) r.get("space_quota_bytes")).longValue();
        if (nq >= 0 || sq >= 0) storage.setQuota(path, nq, sq);
      } finally {
        factory.close();
      }
      db.sql(
              "update directory_mapping set provisioning_status='READY',provisioning_error=null,provisioned_at=:n,updated_at=:n where id=:id")
          .param("n", java.sql.Timestamp.from(Instant.now()))
          .param("id", dialect.id(id))
          .update();
    } catch (Exception e) {
      db.sql(
              "update directory_mapping set provisioning_status='FAILED',provisioning_error=:e,updated_at=:n where id=:id")
          .param("e", truncate(describe(e)))
          .param("n", java.sql.Timestamp.from(Instant.now()))
          .param("id", dialect.id(id))
          .update();
      throw new IllegalStateException("Cannot provision HDFS directory " + id, e);
    }
  }

  private static List<String> parseResources(String value) {
    return value == null || value.isBlank()
        ? List.of()
        : Arrays.stream(value.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static String secretPath(String value) {
    if (value == null || value.isBlank()) return null;
    if (!value.startsWith("file:"))
      throw new IllegalArgumentException(
          "Only file: keytab references are resolved by this deployment");
    return java.nio.file.Path.of(URI.create(value)).toString();
  }

  /** Keeps the whole cause chain so operators see the HDFS reason, not just the wrapper. */
  private static String describe(Throwable failure) {
    List<String> parts = new ArrayList<>();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null && !message.isBlank() && !parts.contains(message)) parts.add(message);
      if (current.getCause() == current) break;
    }
    return parts.isEmpty() ? failure.getClass().getSimpleName() : String.join(" | ", parts);
  }

  private static String truncate(String value) {
    if (value == null) return "unknown";
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }
}
