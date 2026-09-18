package io.github.scholiarw.hfg.manager.api;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A Gateway serves exactly one HDFS connection: the one bound to its service group. Granting a user
 * a directory that lives in another connection would publish a virtual path its Gateway can never
 * resolve, so every write path that links a user to a directory checks the binding here.
 */
final class ClusterBindingGuard {
  private ClusterBindingGuard() {}

  /** Validates a new directory mapping against the cluster its initial user is bound to. */
  static void requireUserCluster(JdbcClient db, UUID userId, String clusterId) {
    String userCluster =
        db.sql(
                "select g.hdfs_cluster_id from ftp_user u join service_group g on g.id=u.service_group_id where u.id=:u")
            .param("u", userId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new NoSuchElementException("用户不存在"));
    if (!userCluster.equals(clusterId)) throw mismatch(clusterId, userCluster, null);
  }

  /** Validates an existing directory mapping against the cluster of an existing user. */
  static void requireGrantCluster(JdbcClient db, UUID userId, UUID directoryId) {
    List<Map<String, Object>> rows =
        db.sql(
                "select d.name,d.hdfs_cluster_id as directory_cluster,g.hdfs_cluster_id as user_cluster from directory_mapping d join ftp_user u on u.id=:u join service_group g on g.id=u.service_group_id where d.id=:d")
            .param("u", userId)
            .param("d", directoryId)
            .query()
            .listOfRows();
    if (rows.isEmpty()) throw new NoSuchElementException("用户或目录不存在");
    Map<String, Object> row = rows.get(0);
    String directoryCluster = String.valueOf(row.get("directory_cluster"));
    String userCluster = String.valueOf(row.get("user_cluster"));
    if (!directoryCluster.equals(userCluster))
      throw mismatch(directoryCluster, userCluster, String.valueOf(row.get("name")));
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
