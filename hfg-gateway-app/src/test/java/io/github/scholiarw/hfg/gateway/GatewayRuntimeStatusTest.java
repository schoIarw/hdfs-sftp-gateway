package io.github.scholiarw.hfg.gateway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GatewayRuntimeStatusTest {
  @Test
  void reportsAndClearsComponentErrors() {
    GatewayRuntimeStatus status = new GatewayRuntimeStatus();
    status.failed("hdfs", new IllegalStateException("permission denied"));
    assertEquals("hdfs: permission denied", status.summary());
    status.healthy("hdfs");
    assertEquals("", status.summary());
  }
}
