package io.github.scholiarw.hfg.manager.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("logsDatabase")
final class LogsHealthIndicator implements HealthIndicator {
  private final LogsStore logs;

  LogsHealthIndicator(LogsStore logs) {
    this.logs = logs;
  }

  @Override
  public Health health() {
    try {
      Integer value = logs.jdbc().sql("select 1").query(Integer.class).single();
      return value == 1
          ? Health.up().withDetail("vendor", logs.vendor()).build()
          : Health.down().withDetail("result", value).build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }
}
