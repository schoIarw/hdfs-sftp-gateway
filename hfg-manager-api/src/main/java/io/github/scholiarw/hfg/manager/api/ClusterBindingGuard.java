package io.github.scholiarw.hfg.manager.api;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A Gateway serves exactly one HDFS connection: the one bound to its service group. Granting a user
 * a directory that lives in another connection would publish a virtual path its Gateway can never
 * resolve, so every directory ownership change checks the binding here.
 */
final class ClusterBindingGuard {
  private ClusterBindingGuard() {}

  /** Validates a directory mapping against the cluster its owner is bound to. */
  static void requireUserCluster(
      JdbcClient db, DatabaseDialect dialect, UUID userId, String clusterId) {
    String userCluster =
        db.sql(
                "select g.hdfs_cluster_id from ftp_user u join service_group g on g.id=u.service_group_id where u.id=:u")
            .param("u", dialect.id(userId))
            .query(String.class)
            .optional()
            .orElseThrow(() -> new NoSuchElementException("用户不存在"));
    if (!userCluster.equals(clusterId)) throw mismatch(clusterId, userCluster, null);
  }

  /** Prevents moving a user to a service group whose HDFS connection cannot serve owned paths. */
  static void requireOwnedDirectoriesMatchGroup(
      JdbcClient db, DatabaseDialect dialect, UUID userId, String serviceGroupId) {
    String targetCluster =
        db.sql("select hdfs_cluster_id from service_group where id=:group")
            .param("group", serviceGroupId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new NoSuchElementException("服务组不存在"));
    var mismatches =
        db.sql(
                "select name,hdfs_cluster_id from directory_mapping where owner_user_id=:user and hdfs_cluster_id<>:cluster order by name")
            .param("user", dialect.id(userId))
            .param("cluster", targetCluster)
            .query()
            .listOfRows();
    if (!mismatches.isEmpty()) {
      String names =
          mismatches.stream().map(row -> String.valueOf(row.get("name"))).collect(java.util.stream.Collectors.joining("、"));
      throw new IllegalStateException(
          "用户归属目录（" + names + "）不属于目标服务组的 HDFS 连接 " + targetCluster + "；请先转移或删除这些目录");
    }
  }

  private static IllegalStateException mismatch(
      String directoryCluster, String userCluster, String directoryName) {
    return new IllegalStateException(
        (directoryName == null ? "该目录" : "目录 “" + directoryName + "”")
            + "属于 HDFS 连接 "
            + directoryCluster
            + "，而用户所属服务组绑定 "
            + userCluster
            + "；一个 Gateway 只能访问本服务组的 HDFS 连接，请先调整目录映射或用户的服务组");
  }
}
