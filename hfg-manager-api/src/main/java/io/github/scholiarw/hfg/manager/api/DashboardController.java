package io.github.scholiarw.hfg.manager.api;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {
  private final JdbcClient management;
  private final LogsStore logs;
  private final DatabaseDialect managementDialect;
  private final DirectoryUsageService directoryUsage;

  DashboardController(JdbcClient management, LogsStore logs, DatabaseDialect managementDialect,
      DirectoryUsageService directoryUsage) {
    this.management=management; this.logs=logs; this.managementDialect=managementDialect;
    this.directoryUsage=directoryUsage;
  }

  @GetMapping("/summary")
  Map<String,Object> summary() {
    LocalDate today=LocalDate.now(ZoneOffset.UTC);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("serviceGroups",management.sql("select count(*) from service_group where status='ENABLED'")
        .query(Long.class).single());
    result.put("gatewaysUp",management.sql(
        "select count(*) from gateway_node where status='UP' and last_heartbeat_at>:cutoff")
        .param("cutoff",Instant.now().minusSeconds(30)).query(Long.class).single());
    Map<String,Long> bytes=new HashMap<>();
    for(Map<String,Object> row:logs.jdbc().sql(
        "select direction,coalesce(sum(file_size_bytes),0) bytes from logs "
            +"where log_date=:day and record_type='TRANSFER' and status='COMPLETED' group by direction")
        .param("day",today).query().listOfRows()) bytes.put(String.valueOf(row.get("direction")),number(row.get("bytes")));
    result.put("uploadBytes",bytes.getOrDefault("UPLOAD",0L));
    result.put("downloadBytes",bytes.getOrDefault("DOWNLOAD",0L));
    result.put("completedFiles",logs.jdbc().sql(
        "select count(*) from logs where log_date=:day and record_type='TRANSFER' and status='COMPLETED'")
        .param("day",today).query(Long.class).single());
    return result;
  }

  @GetMapping("/history")
  List<Map<String,Object>> allHistory(@RequestParam Instant from,@RequestParam Instant to,
      @RequestParam(defaultValue="hour") String bucket) {
    validate(from,to);
    return historyQuery(null,from,to,bucket);
  }

  @GetMapping("/users/{userId}/history")
  List<Map<String,Object>> history(@PathVariable UUID userId,@RequestParam Instant from,
      @RequestParam Instant to,@RequestParam(defaultValue="hour") String bucket) {
    validate(from,to);
    return historyQuery(userId,from,to,bucket);
  }

  @GetMapping("/users/{userId}/connections")
  List<Map<String,Object>> connections(@PathVariable UUID userId,@RequestParam Instant from,
      @RequestParam Instant to) {
    validate(from,to);
    String expression=bucketExpression("minute","started_at");
    return logs.jdbc().sql("select "+expression+" bucket,protocol,count(*) opened,"
            +"sum(case when status in('COMPLETED','FAILED','ABORTED') then 1 else 0 end) closed "
            +"from logs where record_type='TRANSFER' and user_id=:user and log_date>=:fromDay "
            +"and log_date<=:toDay and started_at>=:from and started_at<:to group by 1,2 order by 1")
        .param("user",userId).param("fromDay",utcDate(from)).param("toDay",utcDate(to))
        .param("from",from).param("to",to).query().listOfRows();
  }

  @GetMapping("/users/{userId}/directories")
  List<Map<String,Object>> directories(@PathVariable UUID userId) { return directoryUsage.forUser(userId); }

  @GetMapping("/users/{userId}/realtime")
  Map<String,Object> realtime(@PathVariable UUID userId) {
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("uploadConnections",0L); result.put("downloadConnections",0L);
    result.put("uploadBytesLastMinute",0L); result.put("downloadBytesLastMinute",0L);
    for(Map<String,Object> row:logs.jdbc().sql(
        "select direction,sum(case when status='STARTED' then 1 else 0 end) connections,"
            +"coalesce(sum(case when status='COMPLETED' and ended_at>=:cutoff then file_size_bytes else 0 end),0) bytes "
            +"from logs where record_type='TRANSFER' and user_id=:user and log_date>=:day group by direction")
        .param("cutoff",Instant.now().minusSeconds(60)).param("user",userId)
        .param("day",LocalDate.now(ZoneOffset.UTC).minusDays(1)).query().listOfRows()) {
      String prefix="UPLOAD".equals(String.valueOf(row.get("direction")))?"upload":"download";
      result.put(prefix+"Connections",number(row.get("connections")));
      result.put(prefix+"BytesLastMinute",number(row.get("bytes")));
    }
    result.put("sampledAt",Instant.now()); return result;
  }

  @GetMapping("/flow-control")
  List<Map<String,Object>> flowControl() {
    List<Map<String,Object>> policies=management.sql(
        "select u.id user_id,u.username,p.* from ftp_user u left join traffic_policy p on p.user_id=u.id order by u.username")
        .query().listOfRows();
    Map<String,Map<String,Object>> latest=latestQuota().stream().collect(Collectors.toMap(
        row->String.valueOf(row.get("user_id"))+"|"+row.get("direction"),Function.identity(),(a,b)->a));
    List<Map<String,Object>> result=new ArrayList<>();
    for(Map<String,Object> policy:policies) for(String direction:List.of("UPLOAD","DOWNLOAD")) {
      Map<String,Object> row=new LinkedHashMap<>(policy);
      row.put("direction",direction);
      Map<String,Object> quota=latest.get(String.valueOf(policy.get("user_id"))+"|"+direction);
      if(quota!=null) row.putAll(quota);
      else {
        row.put("completed_files",0L); row.put("completed_bytes",0L);
        row.put("reserved_files",0L); row.put("reserved_bytes",0L); row.put("quota_reached",false);
      }
      long currentRate=logs.jdbc().sql(
          "select coalesce(sum(file_size_bytes),0)/60 from logs where record_type='TRANSFER' "
              +"and user_id=:user and direction=:direction and status='COMPLETED' "
              +"and log_date>=:day and ended_at>=:cutoff")
          .param("user",policy.get("user_id")).param("direction",direction)
          .param("day",LocalDate.now(ZoneOffset.UTC).minusDays(1))
          .param("cutoff",Instant.now().minusSeconds(60)).query(Long.class).single();
      row.put("recent_bytes_per_second",currentRate);
      long limit=number(policy.get(direction.equals("UPLOAD")?"upload_bytes_per_second":"download_bytes_per_second"));
      row.put("rate_limit_reached",limit>0&&currentRate>=Math.ceil(limit*.95));
      result.add(row);
    }
    return result;
  }

  @GetMapping("/flow-control/history")
  List<Map<String,Object>> flowControlHistory(@RequestParam Instant from,@RequestParam Instant to) {
    validate(from,to);
    return logs.jdbc().sql(
        "select user_id,username,direction,window_start,window_end,completed_files,completed_bytes,"
            +"reserved_files,reserved_bytes,file_limit,byte_limit,quota_reached,status,updated_at "
            +"from (select l.*,row_number() over(partition by user_id,direction,window_start order by updated_at desc) rn "
            +"from logs l where record_type='QUOTA' and log_date>=:fromDay and log_date<=:toDay "
            +"and window_start<:to and window_end>:from) q where rn=1 order by window_start desc,username,direction")
        .param("fromDay",utcDate(from).minusDays(1)).param("toDay",utcDate(to).plusDays(1))
        .param("from",from).param("to",to).query().listOfRows();
  }

  private List<Map<String,Object>> historyQuery(UUID userId,Instant from,Instant to,String bucket) {
    String user=userId==null?"":" and user_id=:user";
    JdbcClient.StatementSpec statement=logs.jdbc().sql(
        "select "+bucketExpression(bucket,"ended_at")+" bucket,direction,sum(file_size_bytes) bytes,count(*) files "
            +"from logs where record_type='TRANSFER' and status='COMPLETED' and log_date>=:fromDay "
            +"and log_date<=:toDay and ended_at>=:from and ended_at<:to"+user+" group by 1,2 order by 1")
        .param("fromDay",utcDate(from)).param("toDay",utcDate(to)).param("from",from).param("to",to);
    if(userId!=null) statement=statement.param("user",userId);
    return statement.query().listOfRows();
  }

  private List<Map<String,Object>> latestQuota() {
    return logs.jdbc().sql(
        "select * from (select l.*,row_number() over(partition by user_id,direction order by updated_at desc) rn "
            +"from logs l where record_type='QUOTA' and log_date>=:day) q where rn=1")
        .param("day",LocalDate.now(ZoneOffset.UTC).minusDays(400)).query().listOfRows();
  }

  private String bucketExpression(String bucket,String column) {
    String unit=switch(bucket){case "minute"->"minute";case "day"->"day";default->"hour";};
    if(logs.vendor()==DatabaseDialect.Vendor.POSTGRESQL) return "date_trunc('"+unit+"',"+column+")";
    String format=switch(unit){case "minute"->"%Y-%m-%d %H:%i:00";case "day"->"%Y-%m-%d 00:00:00";default->"%Y-%m-%d %H:00:00";};
    return "date_format("+column+",'"+format+"')";
  }

  private static LocalDate utcDate(Instant instant){return LocalDate.ofInstant(instant,ZoneOffset.UTC);}
  private static void validate(Instant from,Instant to){if(!from.isBefore(to))throw new IllegalArgumentException("from must be before to");}
  private static long number(Object value){return value==null?0:((Number)value).longValue();}
}
