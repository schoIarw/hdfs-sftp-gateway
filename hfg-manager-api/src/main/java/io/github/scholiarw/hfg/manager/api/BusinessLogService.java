package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
final class BusinessLogService {
  private final JdbcClient management;
  private final DatabaseDialect managementDialect;
  private final LogsStore logs;

  BusinessLogService(JdbcClient management, DatabaseDialect managementDialect, LogsStore logs) {
    this.management = management;
    this.managementDialect = managementDialect;
    this.logs = logs;
  }

  void ingest(TransferEvent event) {
    logs.transaction(
        jdbc -> {
          if (event.status() == TransferStatus.STARTED) insertStarted(jdbc, event);
          else complete(jdbc, event);
          return null;
        });
  }

  private void insertStarted(JdbcClient jdbc, TransferEvent e) {
    String sql =
        logs.vendor() == DatabaseDialect.Vendor.POSTGRESQL
            ? "insert into logs(log_date,record_type,log_id,transfer_id,user_id,username,protocol,direction,status,virtual_path,file_name,file_size_bytes,started_at,gateway_id,client_address,error_code,correlation_id,created_at,updated_at) values(:day,'TRANSFER',:id,:id,:user,:username,:protocol,:direction,:status,:path,:file,0,:at,:gateway,:client,:error,:correlation,:at,:at) on conflict(log_date,record_type,log_id) do nothing"
            : "insert ignore into logs(log_date,record_type,log_id,transfer_id,user_id,username,protocol,direction,status,virtual_path,file_name,file_size_bytes,started_at,gateway_id,client_address,error_code,correlation_id,created_at,updated_at) values(:day,'TRANSFER',:id,:id,:user,:username,:protocol,:direction,:status,:path,:file,0,:at,:gateway,:client,:error,:correlation,:at,:at)";
    bindEvent(jdbc.sql(sql), e).update();
  }

  private void complete(JdbcClient jdbc, TransferEvent e) {
    Optional<Map<String, Object>> existing =
        jdbc
            .sql(
                "select log_date,started_at from logs where record_type='TRANSFER' and transfer_id=:id order by created_at desc limit 1")
            .param("id", logs.id(e.transferId()))
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    Instant started = existing.map(row -> instant(row.get("started_at"))).orElse(e.occurredAt());
    long duration = Math.max(0, Duration.between(started, e.occurredAt()).toMillis());
    long speed = duration == 0 ? e.bytes() : Math.round(e.bytes() * 1000.0 / duration);
    if (existing.isPresent()) {
      jdbc.sql(
              "update logs set log_date=:endDay,status=:status,file_size_bytes=:bytes,ended_at=:ended,duration_millis=:duration,average_bytes_per_second=:speed,error_code=:error,updated_at=:ended where log_date=:day and record_type='TRANSFER' and log_id=:id")
          .param("endDay", LocalDate.ofInstant(e.occurredAt(), ZoneOffset.UTC))
          .param("status", e.status().name())
          .param("bytes", e.bytes())
          .param("ended", Timestamp.from(e.occurredAt()))
          .param("duration", duration)
          .param("speed", speed)
          .param("error", e.errorCode() == null ? null : e.errorCode().name())
          .param("day", existing.get().get("log_date"))
          .param("id", logs.id(e.transferId()))
          .update();
      return;
    }
    String sql =
        logs.vendor() == DatabaseDialect.Vendor.POSTGRESQL
            ? "insert into logs(log_date,record_type,log_id,transfer_id,user_id,username,protocol,direction,status,virtual_path,file_name,file_size_bytes,started_at,ended_at,duration_millis,average_bytes_per_second,gateway_id,client_address,error_code,correlation_id,created_at,updated_at) values(:day,'TRANSFER',:id,:id,:user,:username,:protocol,:direction,:status,:path,:file,:bytes,:at,:at,0,:bytes,:gateway,:client,:error,:correlation,:at,:at) on conflict(log_date,record_type,log_id) do nothing"
            : "insert ignore into logs(log_date,record_type,log_id,transfer_id,user_id,username,protocol,direction,status,virtual_path,file_name,file_size_bytes,started_at,ended_at,duration_millis,average_bytes_per_second,gateway_id,client_address,error_code,correlation_id,created_at,updated_at) values(:day,'TRANSFER',:id,:id,:user,:username,:protocol,:direction,:status,:path,:file,:bytes,:at,:at,0,:bytes,:gateway,:client,:error,:correlation,:at,:at)";
    bindEvent(jdbc.sql(sql), e).param("bytes", e.bytes()).update();
  }

  private JdbcClient.StatementSpec bindEvent(JdbcClient.StatementSpec statement, TransferEvent e) {
    return statement
        .param("day", LocalDate.ofInstant(e.occurredAt(), ZoneOffset.UTC))
        .param("id", logs.id(e.transferId()))
        .param("user", logs.id(e.userId()))
        .param("username", username(e.userId()))
        .param("protocol", e.protocol().name())
        .param("direction", e.direction().name())
        .param("status", e.status().name())
        .param("path", e.virtualPath())
        .param("file", fileName(e.virtualPath()))
        .param("at", Timestamp.from(e.occurredAt()))
        .param("gateway", e.gatewayId())
        .param("client", e.clientAddress())
        .param("error", e.errorCode() == null ? null : e.errorCode().name())
        .param("correlation", e.correlationId());
  }

  private String username(UUID userId) {
    return management
        .sql("select username from ftp_user where id=:id")
        .param("id", managementDialect.id(userId))
        .query(String.class)
        .optional()
        .orElse("<deleted>");
  }

  private static String fileName(String path) {
    if (path == null || path.isBlank()) return null;
    int end = path.endsWith("/") ? path.length() - 1 : path.length();
    int slash = path.lastIndexOf('/', end - 1);
    return path.substring(slash + 1, end);
  }

  private static Instant instant(Object value) {
    if (value == null) return Instant.now();
    if (value instanceof Instant instant) return instant;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof OffsetDateTime offset) return offset.toInstant();
    throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass());
  }
}
