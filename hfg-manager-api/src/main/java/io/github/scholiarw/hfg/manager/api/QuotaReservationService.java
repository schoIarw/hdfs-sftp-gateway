package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.traffic.UsageWindow;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class QuotaReservationService {
  private final JdbcClient db;
  private final DatabaseDialect dialect;
  private final BusinessLogService logs;

  QuotaReservationService(JdbcClient db, DatabaseDialect dialect, BusinessLogService logs) {
    this.db = db;
    this.dialect = dialect;
    this.logs = logs;
  }

  @Transactional
  Reservation reserve(UUID userId, TransferDirection direction, long files, long bytes) {
    if (files < 0 || bytes < 0)
      throw new IllegalArgumentException("Reservation cannot be negative");
    Map<String, Object> policy =
        db.sql("select * from traffic_policy where user_id=:u")
            .param("u", dialect.id(userId))
            .query()
            .singleRow();
    TrafficPolicy.Period period =
        TrafficPolicy.Period.valueOf(String.valueOf(policy.get("period")));
    UsageWindow window =
        UsageWindow.containing(
            Instant.now(), period, ZoneId.of(String.valueOf(policy.get("time_zone"))));
    db.sql(
            dialect.choose(
                "insert into usage_window(user_id,direction,window_start,window_end) values(:u,:d,:s,:e) on conflict do nothing",
                "insert ignore into usage_window(user_id,direction,window_start,window_end) values(:u,:d,:s,:e)"))
        .param("u", dialect.id(userId))
        .param("d", direction.name())
        .param("s", java.sql.Timestamp.from(window.startInclusive()))
        .param("e", java.sql.Timestamp.from(window.endExclusive()))
        .update();

    Map<String, Object> usage =
        db.sql(
                "select * from usage_window where user_id=:u and direction=:d and window_start=:s for update")
            .param("u", dialect.id(userId))
            .param("d", direction.name())
            .param("s", java.sql.Timestamp.from(window.startInclusive()))
            .query()
            .singleRow();
    long fileLimit =
        num(
            policy,
            direction == TransferDirection.UPLOAD
                ? "period_upload_files"
                : "period_download_files");
    long byteLimit =
        num(
            policy,
            direction == TransferDirection.UPLOAD
                ? "period_upload_bytes"
                : "period_download_bytes");
    long usedFiles = num(usage, "completed_files") + num(usage, "reserved_files");
    long usedBytes = num(usage, "completed_bytes") + num(usage, "reserved_bytes");
    if (fileLimit > 0 && usedFiles + files > fileLimit)
      throw new HfgException(HfgErrorCode.QUOTA_EXCEEDED, "Periodic file quota exceeded");
    if (byteLimit > 0 && usedBytes + bytes > byteLimit)
      throw new HfgException(HfgErrorCode.QUOTA_EXCEEDED, "Periodic byte quota exceeded");

    UUID id = UUID.randomUUID();
    db.sql(
            "update usage_window set reserved_files=reserved_files+:f,reserved_bytes=reserved_bytes+:b,"
                + "revision=revision+1 where user_id=:u and direction=:d and window_start=:s")
        .param("f", files)
        .param("b", bytes)
        .param("u", dialect.id(userId))
        .param("d", direction.name())
        .param("s", java.sql.Timestamp.from(window.startInclusive()))
        .update();
    db.sql(
            "insert into quota_reservation(id,user_id,direction,window_start,reserved_files,reserved_bytes,"
                + "status,expires_at,created_at) values(:id,:u,:d,:s,:f,:b,'ACTIVE',:expires,:now)")
        .param("id", dialect.id(id))
        .param("u", dialect.id(userId))
        .param("d", direction.name())
        .param("s", java.sql.Timestamp.from(window.startInclusive()))
        .param("f", files)
        .param("b", bytes)
        .param("expires", java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))))
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
    logs.recordQuotaAfterCommit(userId, direction, window.startInclusive(), "RESERVED");
    return new Reservation(id, files, bytes);
  }

  @Transactional
  void commit(UUID id, long completedFiles, long completedBytes) {
    settle(id, completedFiles, completedBytes, "COMMITTED");
  }

  @Transactional
  void renew(UUID id) {
    db.sql("update quota_reservation set expires_at=:expires where id=:id and status='ACTIVE'")
        .param("expires", java.sql.Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))))
        .param("id", dialect.id(id))
        .update();
  }

  @Scheduled(fixedDelayString = "${hfg.quota.expiry-scan:PT1M}")
  @Transactional
  void releaseExpired() {
    List<UUID> ids =
        db
            .sql(
                "select id from quota_reservation where status='ACTIVE' and expires_at<:n limit 100 for update skip locked")
            .param("n", java.sql.Timestamp.from(Instant.now()))
            .query(String.class)
            .list()
            .stream()
            .map(UUID::fromString)
            .toList();
    for (UUID id : ids) settle(id, 0, 0, "RELEASED");
  }

  private void settle(UUID id, long completedFiles, long completedBytes, String finalStatus) {
    Map<String, Object> reservation =
        db.sql("select * from quota_reservation where id=:id for update")
            .param("id", dialect.id(id))
            .query()
            .singleRow();
    if (!"ACTIVE".equals(String.valueOf(reservation.get("status")))) return;
    long reservedFiles = num(reservation, "reserved_files"),
        reservedBytes = num(reservation, "reserved_bytes");
    long files = Math.min(Math.max(0, completedFiles), reservedFiles);
    long bytes = Math.min(Math.max(0, completedBytes), reservedBytes);
    db.sql(
            "update usage_window set reserved_files=greatest(0,reserved_files-:rf),"
                + "reserved_bytes=greatest(0,reserved_bytes-:rb),completed_files=completed_files+:cf,"
                + "completed_bytes=completed_bytes+:cb,revision=revision+1 "
                + "where user_id=:u and direction=:d and window_start=:s")
        .param("rf", reservedFiles)
        .param("rb", reservedBytes)
        .param("cf", files)
        .param("cb", bytes)
        .param("u", reservation.get("user_id"))
        .param("d", reservation.get("direction"))
        .param("s", reservation.get("window_start"))
        .update();
    db.sql("update quota_reservation set status=:status,committed_at=:now where id=:id")
        .param("status", finalStatus)
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .param("id", dialect.id(id))
        .update();
    logs.recordQuotaAfterCommit(
        uuid(reservation.get("user_id")),
        TransferDirection.valueOf(String.valueOf(reservation.get("direction"))),
        instant(reservation.get("window_start")),
        finalStatus);
  }

  private static UUID uuid(Object value) {
    return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof OffsetDateTime offset) return offset.toInstant();
    throw new IllegalArgumentException("Unsupported timestamp: " + value);
  }

  private static long num(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? 0 : ((Number) value).longValue();
  }

  record Reservation(UUID id, long files, long bytes) {}
}
