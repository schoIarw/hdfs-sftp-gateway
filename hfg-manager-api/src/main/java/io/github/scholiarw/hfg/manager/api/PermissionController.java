package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.AccessMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/{userId}/grants")
class PermissionController {
  private final JdbcClient db;
  private final DatabaseDialect dialect;

  PermissionController(JdbcClient db, DatabaseDialect dialect) {
    this.db = db;
    this.dialect = dialect;
  }

  @GetMapping
  List<Map<String, Object>> list(@PathVariable UUID userId) {
    return db.sql(
            "select g.*,d.name,d.virtual_path,d.hdfs_path from directory_grant g join directory_mapping d on d.id=g.directory_mapping_id where g.user_id=:u order by d.virtual_path")
        .param("u", userId)
        .query()
        .listOfRows();
  }

  @PutMapping("/{directoryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void grant(
      @PathVariable UUID userId,
      @PathVariable UUID directoryId,
      @Valid @RequestBody GrantRequest r) {
    requireSameCluster(userId, directoryId);
    db.sql(
            dialect.choose(
                "insert into directory_grant(id,user_id,directory_mapping_id,access_mode,created_at) values(:id,:u,:d,:a,:now) on conflict(user_id,directory_mapping_id) do update set access_mode=excluded.access_mode",
                "insert into directory_grant(id,user_id,directory_mapping_id,access_mode,created_at) values(:id,:u,:d,:a,:now) on duplicate key update access_mode=values(access_mode)"))
        .param("id", UUID.randomUUID())
        .param("u", userId)
        .param("d", directoryId)
        .param("a", r.accessMode().name())
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
  }

  @DeleteMapping("/{directoryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID userId, @PathVariable UUID directoryId) {
    db.sql("delete from directory_grant where user_id=:u and directory_mapping_id=:d")
        .param("u", userId)
        .param("d", directoryId)
        .update();
  }

  /**
   * A Gateway serves exactly one HDFS connection: the one bound to its service group. Granting a
   * user a directory that belongs to another connection would publish a virtual path its Gateway
   * can never resolve, so the mismatch is rejected here instead of failing at transfer time.
   */
  private void requireSameCluster(UUID userId, UUID directoryId) {
    List<Map<String, Object>> rows =
        db.sql(
                "select d.name,d.hdfs_cluster_id as mapping_cluster,g.hdfs_cluster_id as user_cluster from directory_mapping d join ftp_user u on u.id=:u join service_group g on g.id=u.service_group_id where d.id=:d")
            .param("u", userId)
            .param("d", directoryId)
            .query()
            .listOfRows();
    if (rows.isEmpty()) throw new NoSuchElementException("用户或目录不存在");
    Map<String, Object> row = rows.get(0);
    if (!String.valueOf(row.get("mapping_cluster")).equals(String.valueOf(row.get("user_cluster"))))
      throw new IllegalStateException(
          "目录 “"
              + row.get("name")
              + "” 属于 HDFS 连接 "
              + row.get("mapping_cluster")
              + "，而用户所属服务组绑定 "
              + row.get("user_cluster")
              + "；一个 Gateway 只能访问本服务组的 HDFS 连接，请先调整目录映射或用户的服务组");
  }

  record GrantRequest(@NotNull AccessMode accessMode) {}
}
