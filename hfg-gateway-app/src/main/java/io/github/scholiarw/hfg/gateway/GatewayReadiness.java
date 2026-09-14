package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.control.AtomicSnapshotStore;
import io.micrometer.core.instrument.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("hfgSnapshot")
class GatewayReadiness implements HealthIndicator {
  private final AtomicSnapshotStore store;
  private final ReloadableHdfsStorageClientFactory storage;

  GatewayReadiness(
      AtomicSnapshotStore store,
      ReloadableHdfsStorageClientFactory storage,
      MeterRegistry registry) {
    this.store = store;
    this.storage = storage;
    Gauge.builder("hfg_snapshot_version", store, AtomicSnapshotStore::version).register(registry);
  }

  public Health health() {
    if (store.version() == 0)
      return Health.outOfService().withDetail("reason", "No valid snapshot installed").build();
    if (!storage.ready())
      return Health.outOfService().withDetail("reason", "No HDFS configuration installed").build();
    return Health.up().withDetail("snapshotVersion", store.version()).build();
  }
}
