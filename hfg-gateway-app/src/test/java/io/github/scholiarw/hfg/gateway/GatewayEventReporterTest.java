package io.github.scholiarw.hfg.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayEventReporterTest {
  @TempDir Path temp;

  @Test
  void drainsBoundedAsyncQueueToWalOnShutdown() throws Exception {
    Path wal = temp.resolve("events.wal");
    var properties =
        new GatewayProperties(
            null,
            null,
            new GatewayProperties.Snapshot(
                temp.resolve("snapshot.json"), null, wal, Duration.ofSeconds(5)),
            null,
            null,
            null,
            null,
            null);
    var reporter =
        new GatewayEventReporter(
            properties, new ObjectMapper().findAndRegisterModules(), null, new GatewayRuntimeStatus());
    TransferEvent event =
        new TransferEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Protocol.SFTP,
            TransferDirection.UPLOAD,
            TransferStatus.COMPLETED,
            "/file.bin",
            1024,
            Instant.parse("2026-09-23T00:00:00Z"),
            "gateway-a",
            "127.0.0.1",
            null,
            "test");

    for (int i = 0; i < 1000; i++) reporter.publish(event);
    reporter.close();

    assertEquals(1000, Files.readAllLines(wal).size());
  }
}
