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

  PermissionController(JdbcClient db) {
    this.db = db;
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
    db.sql(
            "insert into directory_grant(id,user_id,directory_mapping_id,access_mode,created_at) values(:id,:u,:d,:a,:now) on conflict(user_id,directory_mapping_id) do update set access_mode=excluded.access_mode")
        .param("id", UUID.randomUUID())
        .param("u", userId)
        .param("d", directoryId)
        .param("a", r.accessMode().name())
        .param("now", Instant.now())
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

  record GrantRequest(@NotNull AccessMode accessMode) {}
}
