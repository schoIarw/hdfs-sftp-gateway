package io.github.scholiarw.hfg.manager.api;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {
  private final JdbcClient db;
  private final DirectoryUsageService directoryUsage;

  DashboardController(JdbcClient db, DirectoryUsageService directoryUsage) {
    this.db = db;
    this.directoryUsage = directoryUsage;
  }

  @GetMapping("/summary")
  Map<String, Object> summary() {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put(
        "serviceGroups",
        db.sql("select count(*) from service_group where status='ENABLED'")
            .query(Long.class)
            .single());
    r.put(
        "gatewaysUp",
        db.sql(
                "select count(*) from gateway_node where status='UP' and last_heartbeat_at>now()-interval '30 seconds'")
            .query(Long.class)
            .single());
    r.put(
        "uploadBytes",
        db.sql(
                "select coalesce(sum(bytes),0) from transfer_event where status='COMPLETED' and direction='UPLOAD' and occurred_at>=date_trunc('day',now())")
            .query(Long.class)
            .single());
    r.put(
        "downloadBytes",
        db.sql(
                "select coalesce(sum(bytes),0) from transfer_event where status='COMPLETED' and direction='DOWNLOAD' and occurred_at>=date_trunc('day',now())")
            .query(Long.class)
            .single());
    r.put(
        "completedFiles",
        db.sql(
                "select count(*) from transfer_event where status='COMPLETED' and occurred_at>=date_trunc('day',now())")
            .query(Long.class)
            .single());
    return r;
  }

  @GetMapping("/history")
  List<Map<String, Object>> allHistory(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "hour") String bucket) {
    String unit =
        switch (bucket) {
          case "minute" -> "minute";
          case "day" -> "day";
          default -> "hour";
        };
    return db.sql(
            "select date_trunc('"
                + unit
                + "',occurred_at) as bucket,direction,sum(bytes) as bytes from transfer_event where occurred_at>=:f and occurred_at<:t and status='COMPLETED' group by 1,2 order by 1")
        .param("f", from)
        .param("t", to)
        .query()
        .listOfRows();
  }

  @GetMapping("/users/{userId}/history")
  List<Map<String, Object>> history(
      @PathVariable UUID userId,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "hour") String bucket) {
    String unit =
        switch (bucket) {
          case "minute" -> "minute";
          case "day" -> "day";
          default -> "hour";
        };
    return db.sql(
            "select date_trunc('"
                + unit
                + "',occurred_at) as bucket,direction,sum(bytes) as bytes,count(*) filter(where status='COMPLETED') as files from transfer_event where user_id=:u and occurred_at>=:f and occurred_at<:t and status='COMPLETED' group by 1,2 order by 1")
        .param("u", userId)
        .param("f", from)
        .param("t", to)
        .query()
        .listOfRows();
  }

  @GetMapping("/users/{userId}/connections")
  List<Map<String, Object>> connections(
      @PathVariable UUID userId, @RequestParam Instant from, @RequestParam Instant to) {
    return db.sql(
            "select date_trunc('minute',occurred_at) as bucket,protocol,count(*) filter(where status='STARTED') as opened,count(*) filter(where status in('COMPLETED','FAILED','ABORTED')) as closed from transfer_event where user_id=:u and occurred_at>=:f and occurred_at<:t group by 1,2 order by 1")
        .param("u", userId)
        .param("f", from)
        .param("t", to)
        .query()
        .listOfRows();
  }

  @GetMapping("/users/{userId}/directories")
  List<Map<String, Object>> directories(@PathVariable UUID userId) {
    return directoryUsage.forUser(userId);
  }

  @GetMapping("/users/{userId}/realtime")
  Map<String, Object> realtime(@PathVariable UUID userId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("uploadConnections", 0L);
    result.put("downloadConnections", 0L);
    result.put("uploadBytesLastMinute", 0L);
    result.put("downloadBytesLastMinute", 0L);
    for (Map<String, Object> row :
        db.sql(
                "with latest as (select distinct on(transfer_id) transfer_id,direction,status from transfer_event where user_id=:u order by transfer_id,event_sequence desc), recent as (select direction,coalesce(sum(bytes),0) bytes from transfer_event where user_id=:u and status='COMPLETED' and occurred_at>=now()-interval '1 minute' group by direction) select d.direction,count(l.transfer_id) filter(where l.status='STARTED') as connections,coalesce(r.bytes,0) as bytes from (values('UPLOAD'),('DOWNLOAD')) d(direction) left join latest l on l.direction=d.direction left join recent r on r.direction=d.direction group by d.direction,r.bytes")
            .param("u", userId)
            .query()
            .listOfRows()) {
      String prefix = "UPLOAD".equals(row.get("direction")) ? "upload" : "download";
      result.put(prefix + "Connections", row.get("connections"));
      result.put(prefix + "BytesLastMinute", row.get("bytes"));
    }
    result.put("sampledAt", Instant.now());
    return result;
  }

  @GetMapping("/flow-control")
  List<Map<String, Object>> flowControl() {
    return db.sql(
            """
            select u.id as user_id,u.username,
              coalesce(p.upload_bytes_per_second,0) as upload_bytes_per_second,
              coalesce(p.download_bytes_per_second,0) as download_bytes_per_second,
              coalesce(p.upload_burst_bytes,0) as upload_burst_bytes,
              coalesce(p.download_burst_bytes,0) as download_burst_bytes,
              coalesce(p.max_connections,0) as max_connections,
              coalesce(p.max_upload_transfers,0) as max_upload_transfers,
              coalesce(p.max_download_transfers,0) as max_download_transfers,
              coalesce(p.period,'DAY') as period,
              coalesce(p.period_upload_files,0) as period_upload_files,
              coalesce(p.period_download_files,0) as period_download_files,
              coalesce(p.period_upload_bytes,0) as period_upload_bytes,
              coalesce(p.period_download_bytes,0) as period_download_bytes,
              coalesce(p.time_zone,'UTC') as time_zone,
              d.direction,w.window_start,w.window_end,
              coalesce(w.completed_files,0) as completed_files,
              coalesce(w.completed_bytes,0) as completed_bytes,
              coalesce(w.reserved_files,0) as reserved_files,
              coalesce(w.reserved_bytes,0) as reserved_bytes,
              coalesce(recent.bytes_per_second,0) as recent_bytes_per_second,
              case when d.direction='UPLOAD' then
                coalesce(p.upload_bytes_per_second,0)>0 and coalesce(recent.bytes_per_second,0)>=p.upload_bytes_per_second*0.95
              else
                coalesce(p.download_bytes_per_second,0)>0 and coalesce(recent.bytes_per_second,0)>=p.download_bytes_per_second*0.95
              end as rate_limit_reached,
              case when d.direction='UPLOAD' then
                (coalesce(p.period_upload_files,0)>0 and coalesce(w.completed_files,0)+coalesce(w.reserved_files,0)>=p.period_upload_files)
                or (coalesce(p.period_upload_bytes,0)>0 and coalesce(w.completed_bytes,0)+coalesce(w.reserved_bytes,0)>=p.period_upload_bytes)
              else
                (coalesce(p.period_download_files,0)>0 and coalesce(w.completed_files,0)+coalesce(w.reserved_files,0)>=p.period_download_files)
                or (coalesce(p.period_download_bytes,0)>0 and coalesce(w.completed_bytes,0)+coalesce(w.reserved_bytes,0)>=p.period_download_bytes)
              end as quota_reached
            from ftp_user u
            cross join (values('UPLOAD'),('DOWNLOAD')) d(direction)
            left join traffic_policy p on p.user_id=u.id
            left join lateral (
              select * from usage_window
              where user_id=u.id and direction=d.direction
              order by window_start desc limit 1
            ) w on true
            left join lateral (
              select coalesce(sum(bytes),0)/60 as bytes_per_second
              from transfer_event
              where user_id=u.id and direction=d.direction and status='COMPLETED'
                and occurred_at>=now()-interval '1 minute'
            ) recent on true
            order by u.username,d.direction
            """)
        .query()
        .listOfRows();
  }

  @GetMapping("/flow-control/history")
  List<Map<String, Object>> flowControlHistory(
      @RequestParam Instant from, @RequestParam Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    return db.sql(
            """
            select u.id as user_id,u.username,w.direction,w.window_start,w.window_end,
              w.completed_files,w.completed_bytes,w.reserved_files,w.reserved_bytes,
              case when w.direction='UPLOAD' then coalesce(p.period_upload_files,0)
                   else coalesce(p.period_download_files,0) end as file_limit,
              case when w.direction='UPLOAD' then coalesce(p.period_upload_bytes,0)
                   else coalesce(p.period_download_bytes,0) end as byte_limit,
              case when w.direction='UPLOAD' then
                (coalesce(p.period_upload_files,0)>0 and w.completed_files+w.reserved_files>=p.period_upload_files)
                or (coalesce(p.period_upload_bytes,0)>0 and w.completed_bytes+w.reserved_bytes>=p.period_upload_bytes)
              else
                (coalesce(p.period_download_files,0)>0 and w.completed_files+w.reserved_files>=p.period_download_files)
                or (coalesce(p.period_download_bytes,0)>0 and w.completed_bytes+w.reserved_bytes>=p.period_download_bytes)
              end as quota_reached
            from usage_window w
            join ftp_user u on u.id=w.user_id
            left join traffic_policy p on p.user_id=u.id
            where w.window_start<:to and w.window_end>:from
            order by w.window_start desc,u.username,w.direction
            """)
        .param("from", from)
        .param("to", to)
        .query()
        .listOfRows();
  }
}
