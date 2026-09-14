package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.control.AtomicSnapshotStore;
import io.micrometer.core.instrument.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("hfgSnapshot")
class GatewayReadiness implements HealthIndicator {
  private final AtomicSnapshotStore store;

  GatewayReadiness(AtomicSnapshotStore store, MeterRegistry registry) {
    this.store = store;
    Gauge.builder("hfg_snapshot_version", store, AtomicSnapshotStore::version).register(registry);
  }

  public Health health() {
    return store.version() > 0
        ? Health.up().withDetail("snapshotVersion", store.version()).build()
        : Health.outOfService().withDetail("reason", "No valid snapshot installed").build();
  }
}
