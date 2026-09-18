package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.storage.QuotaUsage;
import io.github.scholiarw.hfg.storage.hdfs.HdfsStorageClientFactory;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class DirectoryUsageService {
  private final JdbcClient db;

  DirectoryUsageService(JdbcClient db) {
    this.db = db;
  }

  List<Map<String, Object>> forUser(UUID userId) {
    return db
        .sql(
            "select d.name,d.virtual_path,d.hdfs_path,d.namespace_quota,d.space_quota_bytes,h.default_fs,h.kerberos_enabled,h.principal,h.keytab_secret_ref,h.config_resource_refs from directory_grant g join directory_mapping d on d.id=g.directory_mapping_id join hdfs_cluster h on h.id=d.hdfs_cluster_id where g.user_id=:u and d.status='ENABLED' order by d.virtual_path")
        .param("u", userId)
        .query()
        .listOfRows()
        .stream()
        .map(this::usage)
        .toList();
  }

  private Map<String, Object> usage(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", row.get("name"));
    result.put("virtualPath", row.get("virtual_path"));
    result.put("hdfsPath", row.get("hdfs_path"));
    result.put("configuredNamespaceQuota", number(row.get("namespace_quota")));
    result.put("configuredSpaceQuotaBytes", number(row.get("space_quota_bytes")));
    try {
      boolean kerberos = Boolean.TRUE.equals(row.get("kerberos_enabled"));
      var factory =
          new HdfsStorageClientFactory(
              new HdfsStorageClientFactory.Settings(
                  (String) row.get("default_fs"),
                  resources((String) row.get("config_resource_refs")),
                  kerberos ? (String) row.get("principal") : null,
                  keytab((String) row.get("keytab_secret_ref")),
                  true));
      try (var storage = factory.forEffectiveUser(null)) {
        QuotaUsage usage = storage.quota((String) row.get("hdfs_path"));
        result.put("namespaceQuota", usage.namespaceQuota());
        result.put("namespaceUsed", usage.namespaceConsumed());
        result.put("spaceQuotaBytes", usage.spaceQuotaBytes());
        result.put("spaceUsedBytes", usage.spaceConsumedBytes());
        result.put("available", true);
      } finally {
        factory.close();
      }
    } catch (Exception exception) {
      result.put("available", false);
      result.put("error", safeMessage(exception));
    }
    return result;
  }

  private static List<String> resources(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays.stream(value.split("[,;]"))
        .map(String::trim)
        .filter(item -> !item.isEmpty())
        .toList();
  }

  private static String keytab(String value) {
    if (value == null || value.isBlank()) return null;
    if (!value.startsWith("file:"))
      throw new IllegalArgumentException("Only file: keytab references are supported");
    return Path.of(URI.create(value)).toString();
  }

  private static long number(Object value) {
    return value == null ? -1 : ((Number) value).longValue();
  }

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null) return exception.getClass().getSimpleName();
    return message.length() > 512 ? message.substring(0, 512) : message;
  }
}
