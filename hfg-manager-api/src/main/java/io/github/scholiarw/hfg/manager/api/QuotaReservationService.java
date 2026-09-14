package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.traffic.UsageWindow;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class QuotaReservationService {
  private final JdbcClient db;

  QuotaReservationService(JdbcClient db) {
    this.db = db;
  }

  @Transactional
  Reservation reserve(UUID userId, TransferDirection direction, long files, long bytes) {
    if (files < 0 || bytes < 0)
      throw new IllegalArgumentException("Reservation cannot be negative");
    Map<String, Object> p =
        db.sql("select * from traffic_policy where user_id=:u")
            .param("u", userId)
            .query()
            .singleRow();
    TrafficPolicy.Period period = TrafficPolicy.Period.valueOf((String) p.get("period"));
    UsageWindow w =
        UsageWindow.containing(Instant.now(), period, ZoneId.of((String) p.get("time_zone")));
    db.sql(
            "insert into usage_window(user_id,direction,window_start,window_end) values(:u,:d,:s,:e) on conflict do nothing")
        .param("u", userId)
        .param("d", direction.name())
        .param("s", w.startInclusive())
        .param("e", w.endExclusive())
        .update();
    Map<String, Object> usage =
        db.sql(
                "select * from usage_window where user_id=:u and direction=:d and window_start=:s for update")
            .param("u", userId)
            .param("d", direction.name())
            .param("s", w.startInclusive())
            .query()
            .singleRow();
    long
        fileLimit =
            num(
                p,
                direction == TransferDirection.UPLOAD
                    ? "period_upload_files"
                    : "period_download_files"),
        byteLimit =
            num(
                p,
                direction == TransferDirection.UPLOAD
                    ? "period_upload_bytes"
                    : "period_download_bytes");
    long usedFiles = num(usage, "completed_files") + num(usage, "reserved_files"),
        usedBytes = num(usage, "completed_bytes") + num(usage, "reserved_bytes");
    if (fileLimit > 0 && usedFiles + files > fileLimit)
      throw new HfgException(HfgErrorCode.QUOTA_EXCEEDED, "Periodic file quota exceeded");
    if (byteLimit > 0 && usedBytes + bytes > byteLimit)
      throw new HfgException(HfgErrorCode.QUOTA_EXCEEDED, "Periodic byte quota exceeded");
    UUID id = UUID.randomUUID();
    db.sql(
            "update usage_window set reserved_files=reserved_files+:f,reserved_bytes=reserved_bytes+:b,revision=revision+1 where user_id=:u and direction=:d and window_start=:s")
        .param("f", files)
        .param("b", bytes)
        .param("u", userId)
        .param("d", direction.name())
        .param("s", w.startInclusive())
        .update();
    db.sql(
            "insert into quota_reservation(id,user_id,direction,window_start,reserved_files,reserved_bytes,status,expires_at,created_at) values(:id,:u,:d,:s,:f,:b,'ACTIVE',:expires,:now)")
        .param("id", id)
        .param("u", userId)
        .param("d", direction.name())
        .param("s", w.startInclusive())
        .param("f", files)
        .param("b", bytes)
        .param("expires", Instant.now().plus(Duration.ofMinutes(15)))
        .param("now", Instant.now())
        .update();
    return new Reservation(id, files, bytes);
  }

  @Transactional
  void commit(UUID id, long completedFiles, long completedBytes) {
    Map<String, Object> r =
        db.sql("select * from quota_reservation where id=:id for update")
            .param("id", id)
            .query()
            .singleRow();
    if (!"ACTIVE".equals(r.get("status"))) return;
    long rf = num(r, "reserved_files"), rb = num(r, "reserved_bytes");
    long cf = Math.min(Math.max(0, completedFiles), rf),
        cb = Math.min(Math.max(0, completedBytes), rb);
    db.sql(
            "update usage_window set reserved_files=greatest(0,reserved_files-:rf),reserved_bytes=greatest(0,reserved_bytes-:rb),completed_files=completed_files+:cf,completed_bytes=completed_bytes+:cb,revision=revision+1 where user_id=:u and direction=:d and window_start=:s")
        .param("rf", rf)
        .param("rb", rb)
        .param("cf", cf)
        .param("cb", cb)
        .param("u", r.get("user_id"))
        .param("d", r.get("direction"))
        .param("s", r.get("window_start"))
        .update();
    db.sql("update quota_reservation set status='COMMITTED',committed_at=:n where id=:id")
        .param("n", Instant.now())
        .param("id", id)
        .update();
  }

  @Transactional
  void renew(UUID id) {
    db.sql("update quota_reservation set expires_at=:expires where id=:id and status='ACTIVE'")
        .param("expires", Instant.now().plus(Duration.ofMinutes(15)))
        .param("id", id)
        .update();
  }

  @Scheduled(fixedDelayString = "${hfg.quota.expiry-scan:PT1M}")
  @Transactional
  void releaseExpired() {
    List<UUID> ids =
        db.sql(
                "select id from quota_reservation where status='ACTIVE' and expires_at<:n limit 100 for update skip locked")
            .param("n", Instant.now())
            .query(UUID.class)
            .list();
    for (UUID id : ids) commit(id, 0, 0);
  }

  private static long num(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? 0 : ((Number) v).longValue();
  }

  record Reservation(UUID id, long files, long bytes) {}
}
