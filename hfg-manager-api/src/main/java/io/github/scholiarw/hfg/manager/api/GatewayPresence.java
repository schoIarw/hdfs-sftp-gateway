package io.github.scholiarw.hfg.manager.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Single definition of "this gateway is still alive" for the whole Manager: a node counts as online
 * only while its heartbeat keeps arriving. Everything else (stopped process, corrupted state,
 * network loss) is treated as offline, so it never blocks administrative cleanup.
 */
final class GatewayPresence {
  static final Duration TIMEOUT = Duration.ofSeconds(30);

  private GatewayPresence() {}

  static Instant cutoff() {
    return Instant.now().minus(TIMEOUT);
  }
}
