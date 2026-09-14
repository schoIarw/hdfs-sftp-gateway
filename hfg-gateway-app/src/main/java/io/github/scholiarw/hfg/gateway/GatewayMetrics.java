package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.transfer.TransferEventSink;
import io.micrometer.core.instrument.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
class GatewayMetrics implements TransferEventSink {
  private final MeterRegistry registry;
  private final AtomicInteger activeUploads = new AtomicInteger();
  private final AtomicInteger activeDownloads = new AtomicInteger();

  GatewayMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("hfg_transfers_active", activeUploads, AtomicInteger::get)
        .tag("direction", "upload")
        .register(registry);
    Gauge.builder("hfg_transfers_active", activeDownloads, AtomicInteger::get)
        .tag("direction", "download")
        .register(registry);
  }

  public void publish(TransferEvent event) {
    AtomicInteger active =
        event.direction() == TransferDirection.UPLOAD ? activeUploads : activeDownloads;
    if (event.status() == TransferStatus.STARTED) active.incrementAndGet();
    else if (event.status() == TransferStatus.COMPLETED
        || event.status() == TransferStatus.FAILED
        || event.status() == TransferStatus.ABORTED) active.updateAndGet(v -> Math.max(0, v - 1));
    registry
        .counter(
            "hfg_transfer_events_total",
            "protocol",
            event.protocol().name().toLowerCase(),
            "direction",
            event.direction().name().toLowerCase(),
            "status",
            event.status().name().toLowerCase())
        .increment();
    if (event.status() == TransferStatus.COMPLETED)
      registry
          .counter(
              "hfg_transfer_bytes_total",
              "protocol",
              event.protocol().name().toLowerCase(),
              "direction",
              event.direction().name().toLowerCase())
          .increment(event.bytes());
  }
}
