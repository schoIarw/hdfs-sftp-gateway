package io.github.scholiarw.hfg.manager.api;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {
  private final JdbcClient management;
  private final LogsStore logs;
  private final DirectoryUsageService directoryUsage;

  DashboardController(
      JdbcClient management,
      LogsStore logs,
      DirectoryUsageService directoryUsage) {
    this.management = management;
    this.logs = logs;
    this.directoryUsage = directoryUsage;
  }

  @GetMapping("/summary")
  Map<String, Object> summary() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "serviceGroups",
        management
            .sql("select count(*) from service_group where status='ENABLED'")
            .query(Long.class)
            .single());
    result.put(
        "gatewaysUp",
        management
            .sql(
                "select count(*) from gateway_node where status='UP' and last_heartbeat_at>:cutoff")
            .param("cutoff", java.sql.Timestamp.from(GatewayPresence.cutoff()))
            .query(Long.class)
            .single());
    Map<String, Long> bytes = new HashMap<>();
    for (Map<String, Object> row :
        logs.jdbc()
            .sql(
                "select direction,coalesce(sum(file_size_bytes),0) bytes from logs "
                    + "where log_date=:day and record_type='TRANSFER' and status='COMPLETED' group by direction")
            .param("day", today)
            .query()
            .listOfRows())
      bytes.put(String.valueOf(row.get("direction")), number(row.get("bytes")));
    result.put("uploadBytes", bytes.getOrDefault("UPLOAD", 0L));
    result.put("downloadBytes", bytes.getOrDefault("DOWNLOAD", 0L));
    result.put(
        "completedFiles",
        logs.jdbc()
            .sql(
                "select count(*) from logs where log_date=:day and record_type='TRANSFER' and status='COMPLETED'")
            .param("day", today)
            .query(Long.class)
            .single());
    return result;
  }

  @GetMapping("/history")
  List<Map<String, Object>> allHistory(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "hour") String bucket) {
    validate(from, to);
    return historyQuery(null, from, to, bucket);
  }

  @GetMapping("/users/{userId}/history")
  List<Map<String, Object>> history(
      @PathVariable UUID userId,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "hour") String bucket) {
    validate(from, to);
    return historyQuery(userId, from, to, bucket);
  }

  /**
   * Business metrics grouped by user. With no user filter every user remains a separate series;
   * this is intentionally different from the overall history endpoint, which has no user
   * dimension.
   */
  @GetMapping("/user-history")
  List<Map<String, Object>> userHistory(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "hour") String bucket,
      @RequestParam(required = false) UUID userId) {
    validate(from, to);
    String filter = userId == null ? "" : " and user_id=:user";
    JdbcClient.StatementSpec statement =
        logs.jdbc()
            .sql(
                "select "
                    + bucketExpression(bucket, "ended_at")
                    + " bucket,user_id,username,direction,sum(file_size_bytes) bytes,count(*) files "
                    + "from logs where record_type='TRANSFER' and status='COMPLETED' and log_date>=:fromDay "
                    + "and log_date<=:toDay and ended_at>=:from and ended_at<:to"
                    + filter
                    + " group by 1,2,3,4 order by 1,3,4")
            .param("fromDay", utcDate(from))
            .param("toDay", utcDate(to))
            .param("from", java.sql.Timestamp.from(from))
            .param("to", java.sql.Timestamp.from(to));
    if (userId != null) statement = statement.param("user", logs.id(userId));
    return statement.query().listOfRows();
  }

  @GetMapping("/users/{userId}/connections")
  List<Map<String, Object>> connections(
      @PathVariable UUID userId, @RequestParam Instant from, @RequestParam Instant to) {
    validate(from, to);
    String expression = bucketExpression("minute", "started_at");
    return logs.jdbc()
        .sql(
            "select "
                + expression
                + " bucket,protocol,count(*) opened,"
                + "sum(case when status in('COMPLETED','FAILED','ABORTED') then 1 else 0 end) closed "
                + "from logs where record_type='TRANSFER' and user_id=:user and log_date>=:fromDay "
                + "and log_date<=:toDay and started_at>=:from and started_at<:to group by 1,2 order by 1")
        .param("user", logs.id(userId))
        .param("fromDay", utcDate(from))
        .param("toDay", utcDate(to))
        .param("from", java.sql.Timestamp.from(from))
        .param("to", java.sql.Timestamp.from(to))
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
        logs.jdbc()
            .sql(
                "select direction,sum(case when status='STARTED' then 1 else 0 end) connections,"
                    + "coalesce(sum(case when status='COMPLETED' and ended_at>=:cutoff then file_size_bytes else 0 end),0) bytes "
                    + "from logs where record_type='TRANSFER' and user_id=:user and log_date>=:day group by direction")
            .param("cutoff", java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
            .param("user", logs.id(userId))
            .param("day", LocalDate.now(ZoneOffset.UTC).minusDays(1))
            .query()
            .listOfRows()) {
      String prefix = "UPLOAD".equals(String.valueOf(row.get("direction"))) ? "upload" : "download";
      result.put(prefix + "Connections", number(row.get("connections")));
      result.put(prefix + "BytesLastMinute", number(row.get("bytes")));
    }
    result.put("sampledAt", Instant.now());
    return result;
  }

  @GetMapping("/flow-control")
  List<Map<String, Object>> flowControl() {
    List<Map<String, Object>> result =
        management
            .sql(
                "select u.id user_id,u.username,coalesce(p.upload_bytes_per_second,0) upload_bytes_per_second,coalesce(p.download_bytes_per_second,0) download_bytes_per_second,coalesce(p.max_connections,0) max_connections from ftp_user u left join traffic_policy p on p.user_id=u.id order by u.username")
            .query()
            .listOfRows();
    for (Map<String, Object> row : result) {
      for (String direction : List.of("UPLOAD", "DOWNLOAD")) {
        long currentRate = recentRate(row.get("user_id"), direction);
        String prefix = direction.toLowerCase();
        long limit = number(row.get(prefix + "_bytes_per_second"));
        row.put(prefix + "_recent_bytes_per_second", currentRate);
        row.put(prefix + "_rate_limit_reached", limit > 0 && currentRate >= Math.ceil(limit * .95));
      }
    }
    return result;
  }

  @GetMapping("/flow-control/history")
  List<Map<String, Object>> flowControlHistory(
      @RequestParam Instant from, @RequestParam Instant to) {
    validate(from, to);
    return logs.jdbc()
        .sql(
            "select "
                + bucketExpression("minute", "ended_at")
                + " bucket,user_id,username,direction,sum(file_size_bytes) bytes,count(*) files "
                + "from logs where record_type='TRANSFER' and status='COMPLETED' and log_date>=:fromDay "
                + "and log_date<=:toDay and ended_at>=:from and ended_at<:to "
                + "group by 1,2,3,4 order by 1 desc,3,4")
        .param("fromDay", utcDate(from))
        .param("toDay", utcDate(to))
        .param("from", java.sql.Timestamp.from(from))
        .param("to", java.sql.Timestamp.from(to))
        .query()
        .listOfRows();
  }

  private long recentRate(Object userId, String direction) {
    return logs.jdbc()
        .sql(
            "select coalesce(sum(file_size_bytes),0)/60 from logs where record_type='TRANSFER' "
                + "and user_id=:user and direction=:direction and status='COMPLETED' "
                + "and log_date>=:day and ended_at>=:cutoff")
        .param("user", logs.id(userId))
        .param("direction", direction)
        .param("day", LocalDate.now(ZoneOffset.UTC).minusDays(1))
        .param("cutoff", java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
        .query(Long.class)
        .single();
  }

  private List<Map<String, Object>> historyQuery(
      UUID userId, Instant from, Instant to, String bucket) {
    String user = userId == null ? "" : " and user_id=:user";
    JdbcClient.StatementSpec statement =
        logs.jdbc()
            .sql(
                "select "
                    + bucketExpression(bucket, "ended_at")
                    + " bucket,direction,sum(file_size_bytes) bytes,count(*) files "
                    + "from logs where record_type='TRANSFER' and status='COMPLETED' and log_date>=:fromDay "
                    + "and log_date<=:toDay and ended_at>=:from and ended_at<:to"
                    + user
                    + " group by 1,2 order by 1")
            .param("fromDay", utcDate(from))
            .param("toDay", utcDate(to))
            .param("from", java.sql.Timestamp.from(from))
            .param("to", java.sql.Timestamp.from(to));
    if (userId != null) statement = statement.param("user", logs.id(userId));
    return statement.query().listOfRows();
  }

  private String bucketExpression(String bucket, String column) {
    String unit =
        switch (bucket) {
          case "minute" -> "minute";
          case "day" -> "day";
          default -> "hour";
        };
    if (logs.vendor() == DatabaseDialect.Vendor.POSTGRESQL)
      return "date_trunc('" + unit + "'," + column + ")";
    String format =
        switch (unit) {
          case "minute" -> "%Y-%m-%d %H:%i:00";
          case "day" -> "%Y-%m-%d 00:00:00";
          default -> "%Y-%m-%d %H:00:00";
        };
    return "date_format(" + column + ",'" + format + "')";
  }

  private static LocalDate utcDate(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void validate(Instant from, Instant to) {
    if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
  }

  private static long number(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }
}
