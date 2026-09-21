package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.TrafficPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/traffic-policy")
class TrafficPolicyController {
  private final JdbcClient db;
  private final DatabaseDialect dialect;

  TrafficPolicyController(JdbcClient db, DatabaseDialect dialect) {
    this.db = db;
    this.dialect = dialect;
  }

  @GetMapping
  Map<String, Object> get(@PathVariable UUID userId) {
    return db
        .sql(
            "select user_id,upload_bytes_per_second,download_bytes_per_second,max_connections,updated_at from traffic_policy where user_id=:u")
        .param("u", dialect.id(userId))
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElse(Map.of("user_id", userId));
  }

  @PutMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void put(@PathVariable UUID userId, @Valid @RequestBody PolicyRequest request) {
    new TrafficPolicy(
        request.uploadBytesPerSecond, request.downloadBytesPerSecond, request.maxConnections);
    db.sql(
            dialect.choose(
                "insert into traffic_policy(user_id,upload_bytes_per_second,download_bytes_per_second,max_connections,updated_at) values(:u,:upload,:download,:connections,:now) on conflict(user_id) do update set upload_bytes_per_second=excluded.upload_bytes_per_second,download_bytes_per_second=excluded.download_bytes_per_second,max_connections=excluded.max_connections,updated_at=excluded.updated_at",
                "insert into traffic_policy(user_id,upload_bytes_per_second,download_bytes_per_second,max_connections,updated_at) values(:u,:upload,:download,:connections,:now) on duplicate key update upload_bytes_per_second=values(upload_bytes_per_second),download_bytes_per_second=values(download_bytes_per_second),max_connections=values(max_connections),updated_at=values(updated_at)"))
        .param("u", dialect.id(userId))
        .param("upload", request.uploadBytesPerSecond)
        .param("download", request.downloadBytesPerSecond)
        .param("connections", request.maxConnections)
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
  }

  record PolicyRequest(
      @Min(0) long uploadBytesPerSecond,
      @Min(0) long downloadBytesPerSecond,
      @Min(0) int maxConnections) {}
}
