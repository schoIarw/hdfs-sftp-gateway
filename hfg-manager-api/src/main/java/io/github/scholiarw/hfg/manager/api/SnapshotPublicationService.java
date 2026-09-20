package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reliably coalesces configuration mutations into signed snapshots for each service group. */
@Service
class SnapshotPublicationService {
  private static final Logger log = LoggerFactory.getLogger(SnapshotPublicationService.class);
  private final JdbcClient db;
  private final DatabaseDialect dialect;
  private final SnapshotPublisher publisher;
  private final ControlPlaneGrpcService control;
  private final Duration retryDelay;

  SnapshotPublicationService(
      JdbcClient db,
      DatabaseDialect dialect,
      SnapshotPublisher publisher,
      ControlPlaneGrpcService control,
      @Value("${hfg.snapshot.retry-delay:PT30S}") Duration retryDelay) {
    this.db = db;
    this.dialect = dialect;
    this.publisher = publisher;
    this.control = control;
    this.retryDelay = retryDelay;
  }

  void requestAll(String actor, String reason) {
    for (String group :
        db.sql("select id from service_group where status='ENABLED' order by id")
            .query(String.class)
            .list()) request(group, actor, reason);
  }

  void request(String group, String actor, String reason) {
    Instant now = Instant.now();
    db.sql(
            dialect.choose(
                "insert into snapshot_publish_request(service_group_id,requested_at,requested_by,reason,attempts,next_attempt_at,last_error) values(:g,:now,:actor,:reason,0,:now,null) on conflict(service_group_id) do update set requested_at=excluded.requested_at,requested_by=excluded.requested_by,reason=excluded.reason,attempts=0,next_attempt_at=excluded.next_attempt_at,last_error=null",
                "insert into snapshot_publish_request(service_group_id,requested_at,requested_by,reason,attempts,next_attempt_at,last_error) values(:g,:now,:actor,:reason,0,:now,null) on duplicate key update requested_at=values(requested_at),requested_by=values(requested_by),reason=values(reason),attempts=0,next_attempt_at=values(next_attempt_at),last_error=null"))
        .param("g", group)
        .param("now", Timestamp.from(now))
        .param("actor", truncate(actor, 128))
        .param("reason", truncate(reason, 512))
        .update();
  }

  @Scheduled(fixedDelayString = "${hfg.snapshot.auto-publish-interval:PT2S}")
  void publishPending() {
    List<Map<String, Object>> requests =
        db.sql(
                "select service_group_id,requested_at,requested_by from snapshot_publish_request where next_attempt_at<=:now order by requested_at limit 50")
            .param("now", Timestamp.from(Instant.now()))
            .query()
            .listOfRows();
    for (Map<String, Object> request : requests) publish(request);
  }

  @Scheduled(
      initialDelayString = "${hfg.snapshot.reconcile-initial-delay:PT15S}",
      fixedDelayString = "${hfg.snapshot.reconcile-interval:PT5M}")
  void reconcile() {
    try {
      requestAll("system", "periodic snapshot consistency check");
    } catch (RuntimeException exception) {
      log.warn("Cannot schedule periodic snapshot consistency check", exception);
    }
  }

  private void publish(Map<String, Object> request) {
    String group = String.valueOf(request.get("service_group_id"));
    Object requestedAt = request.get("requested_at");
    String actor = String.valueOf(request.get("requested_by"));
    try {
      Optional<SignedSnapshotEnvelope> published = publisher.publishIfChanged(group, actor);
      published.ifPresent(envelope -> control.broadcast(group, envelope));
      db.sql(
              "delete from snapshot_publish_request where service_group_id=:g and requested_at=:requested")
          .param("g", group)
          .param("requested", requestedAt)
          .update();
      if (published.isPresent()) log.info("Automatically published configuration for {}", group);
    } catch (RuntimeException exception) {
      db.sql(
              "update snapshot_publish_request set attempts=attempts+1,next_attempt_at=:retry,last_error=:error where service_group_id=:g and requested_at=:requested")
          .param("retry", Timestamp.from(Instant.now().plus(retryDelay)))
          .param("error", truncate(describe(exception), 2048))
          .param("g", group)
          .param("requested", requestedAt)
          .update();
      log.warn("Automatic snapshot publication failed for {}", group, exception);
    }
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static String truncate(String value, int maximum) {
    String normalized = value == null || value.isBlank() ? "unknown" : value;
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
  }
}
