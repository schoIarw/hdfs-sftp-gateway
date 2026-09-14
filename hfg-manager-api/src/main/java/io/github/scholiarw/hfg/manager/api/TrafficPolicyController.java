package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.TrafficPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

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
        .sql("select * from traffic_policy where user_id=:u")
        .param("u", userId)
        .query()
        .listOfRows()
        .stream()
        .findFirst()
        .orElse(Map.of("user_id", userId));
  }

  @PutMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void put(@PathVariable UUID userId, @Valid @RequestBody PolicyRequest r) {
    new TrafficPolicy(
        r.uploadBytesPerSecond,
        r.downloadBytesPerSecond,
        r.uploadBurstBytes,
        r.downloadBurstBytes,
        r.maxConnections,
        r.maxUploadTransfers,
        r.maxDownloadTransfers,
        r.periodUploadFiles,
        r.periodDownloadFiles,
        r.periodUploadBytes,
        r.periodDownloadBytes,
        r.period,
        r.timeZone);
    db.sql(dialect.choose(
            "insert into traffic_policy(user_id,upload_bytes_per_second,download_bytes_per_second,upload_burst_bytes,download_burst_bytes,max_connections,max_upload_transfers,max_download_transfers,period,period_upload_files,period_download_files,period_upload_bytes,period_download_bytes,time_zone,updated_at) values(:u,:ur,:dr,:ub,:db,:mc,:mu,:md,:p,:uf,:df,:uby,:dby,:tz,:now) on conflict(user_id) do update set upload_bytes_per_second=excluded.upload_bytes_per_second,download_bytes_per_second=excluded.download_bytes_per_second,upload_burst_bytes=excluded.upload_burst_bytes,download_burst_bytes=excluded.download_burst_bytes,max_connections=excluded.max_connections,max_upload_transfers=excluded.max_upload_transfers,max_download_transfers=excluded.max_download_transfers,period=excluded.period,period_upload_files=excluded.period_upload_files,period_download_files=excluded.period_download_files,period_upload_bytes=excluded.period_upload_bytes,period_download_bytes=excluded.period_download_bytes,time_zone=excluded.time_zone,updated_at=excluded.updated_at",
            "insert into traffic_policy(user_id,upload_bytes_per_second,download_bytes_per_second,upload_burst_bytes,download_burst_bytes,max_connections,max_upload_transfers,max_download_transfers,period,period_upload_files,period_download_files,period_upload_bytes,period_download_bytes,time_zone,updated_at) values(:u,:ur,:dr,:ub,:db,:mc,:mu,:md,:p,:uf,:df,:uby,:dby,:tz,:now) on duplicate key update upload_bytes_per_second=values(upload_bytes_per_second),download_bytes_per_second=values(download_bytes_per_second),upload_burst_bytes=values(upload_burst_bytes),download_burst_bytes=values(download_burst_bytes),max_connections=values(max_connections),max_upload_transfers=values(max_upload_transfers),max_download_transfers=values(max_download_transfers),period=values(period),period_upload_files=values(period_upload_files),period_download_files=values(period_download_files),period_upload_bytes=values(period_upload_bytes),period_download_bytes=values(period_download_bytes),time_zone=values(time_zone),updated_at=values(updated_at)"))
        .param("u", userId)
        .param("ur", r.uploadBytesPerSecond)
        .param("dr", r.downloadBytesPerSecond)
        .param("ub", r.uploadBurstBytes)
        .param("db", r.downloadBurstBytes)
        .param("mc", r.maxConnections)
        .param("mu", r.maxUploadTransfers)
        .param("md", r.maxDownloadTransfers)
        .param("p", r.period.name())
        .param("uf", r.periodUploadFiles)
        .param("df", r.periodDownloadFiles)
        .param("uby", r.periodUploadBytes)
        .param("dby", r.periodDownloadBytes)
        .param("tz", r.timeZone)
        .param("now", Instant.now())
        .update();
  }

  record PolicyRequest(
      @Min(0) long uploadBytesPerSecond,
      @Min(0) long downloadBytesPerSecond,
      @Min(0) long uploadBurstBytes,
      @Min(0) long downloadBurstBytes,
      @Min(0) int maxConnections,
      @Min(0) int maxUploadTransfers,
      @Min(0) int maxDownloadTransfers,
      @Min(0) long periodUploadFiles,
      @Min(0) long periodDownloadFiles,
      @Min(0) long periodUploadBytes,
      @Min(0) long periodDownloadBytes,
      TrafficPolicy.Period period,
      String timeZone) {}
}
