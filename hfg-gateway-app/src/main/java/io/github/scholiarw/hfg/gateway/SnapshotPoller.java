package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.control.AtomicSnapshotStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class SnapshotPoller {
  private static final Logger log = LoggerFactory.getLogger(SnapshotPoller.class);
  private final GatewayProperties p;
  private final AtomicSnapshotStore store;
  private final RestClient client;

  SnapshotPoller(GatewayProperties p, AtomicSnapshotStore store, RestClient.Builder builder) {
    this.p = p;
    this.store = store;
    this.client = builder.build();
  }

  @Scheduled(fixedDelayString = "${hfg.snapshot.refresh-interval:PT10S}")
  void refresh() {
    if (p.rpc().enabled()
        || p.snapshot().managerUrl() == null
        || p.snapshot().managerUrl().isBlank()) return;
    try {
      String auth =
          Base64.getEncoder()
              .encodeToString(
                  (p.snapshot().username() + ":" + p.snapshot().password())
                      .getBytes(StandardCharsets.UTF_8));
      SignedSnapshotEnvelope envelope =
          client
              .get()
              .uri(
                  p.snapshot().managerUrl()
                      + "/api/v1/control/snapshots/"
                      + p.serviceGroupId()
                      + "/latest")
              .header("Authorization", "Basic " + auth)
              .retrieve()
              .body(SignedSnapshotEnvelope.class);
      if (envelope != null && store.install(envelope))
        log.info("Installed HFG snapshot version {}", store.version());
    } catch (Exception e) {
      log.warn(
          "Snapshot refresh failed; continuing with version {}: {}",
          store.version(),
          e.getMessage());
    }
  }
}
