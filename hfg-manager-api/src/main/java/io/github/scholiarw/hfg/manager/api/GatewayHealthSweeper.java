package io.github.scholiarw.hfg.manager.api;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks gateways that stopped sending heartbeats as OFFLINE. Without this sweep a killed or
 * disconnected gateway keeps showing UP forever, because its row is only ever written by incoming
 * heartbeats.
 */
@Component
class GatewayHealthSweeper {
  private static final Logger log = LoggerFactory.getLogger(GatewayHealthSweeper.class);
  private final JdbcClient db;

  GatewayHealthSweeper(JdbcClient db) {
    this.db = db;
  }

  @Scheduled(fixedDelayString = "${hfg.gateway.sweep-interval:PT10S}")
  @Transactional
  void sweep() {
    Instant now = Instant.now();
    int offline =
        db.sql(
                "update gateway_node set status='OFFLINE', last_error='心跳超时，网关已离线', updated_at=:now where status in ('UP','DEGRADED') and (last_heartbeat_at is null or last_heartbeat_at<:cutoff)")
            .param("now", java.sql.Timestamp.from(now))
            .param("cutoff", java.sql.Timestamp.from(GatewayPresence.cutoff()))
            .update();
    if (offline > 0)
      log.info(
          "Marked {} gateway node(s) OFFLINE after {}s without a heartbeat",
          offline,
          GatewayPresence.TIMEOUT.toSeconds());
  }
}
