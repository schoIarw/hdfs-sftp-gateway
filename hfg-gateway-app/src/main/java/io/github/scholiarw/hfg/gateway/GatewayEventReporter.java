package io.github.scholiarw.hfg.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.TransferEvent;
import io.github.scholiarw.hfg.control.GrpcControlClient;
import io.github.scholiarw.hfg.transfer.TransferEventSink;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class GatewayEventReporter implements TransferEventSink {
  private static final Logger log = LoggerFactory.getLogger(GatewayEventReporter.class);
  private static final int BATCH_SIZE = 500;
  private final ObjectMapper mapper;
  private final GatewayProperties properties;
  private final GrpcControlClient control;
  private final GatewayRuntimeStatus runtimeStatus;
  private final ReentrantLock appendLock = new ReentrantLock();

  GatewayEventReporter(
      GatewayProperties properties,
      ObjectMapper mapper,
      GrpcControlClient control,
      GatewayRuntimeStatus runtimeStatus) {
    this.properties = properties;
    this.mapper = mapper;
    this.control = control;
    this.runtimeStatus = runtimeStatus;
  }

  @Override
  public void publish(TransferEvent event) {
    appendLock.lock();
    try {
      Path wal = properties.snapshot().eventWalPath();
      Files.createDirectories(wal.toAbsolutePath().getParent());
      Files.writeString(
          wal,
          mapper.writeValueAsString(event) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      log.error("Cannot append transfer event WAL", exception);
    } finally {
      appendLock.unlock();
    }
  }

  @Scheduled(fixedDelayString = "${hfg.snapshot.event-report-interval:PT5S}")
  void flush() {
    Path wal = properties.snapshot().eventWalPath();
    Path sending = wal.resolveSibling(wal.getFileName() + ".sending");
    try {
      if (!prepareSending(wal, sending)) return;
      drainBatch(sending);
      runtimeStatus.healthy("event-reporting");
    } catch (Exception exception) {
      runtimeStatus.failed("event-reporting", exception);
      log.warn("Transfer event report failed; WAL retained: {}", exception.getMessage());
    }
  }

  private boolean prepareSending(Path wal, Path sending) throws IOException {
    if (Files.exists(sending) && Files.size(sending) > 0) return true;
    appendLock.lock();
    try {
      Files.deleteIfExists(sending);
      if (!Files.exists(wal) || Files.size(wal) == 0) return false;
      move(wal, sending);
      return true;
    } finally {
      appendLock.unlock();
    }
  }

  private void drainBatch(Path sending) throws IOException {
    List<TransferEvent> batch = new ArrayList<>(BATCH_SIZE);
    Path remainder = sending.resolveSibling(sending.getFileName() + ".tmp");
    boolean hasRemainder = false;
    try (BufferedReader reader = Files.newBufferedReader(sending, StandardCharsets.UTF_8)) {
      String line;
      while (batch.size() < BATCH_SIZE && (line = reader.readLine()) != null) {
        batch.add(mapper.readValue(line, TransferEvent.class));
      }
      if (batch.isEmpty()) {
        Files.deleteIfExists(sending);
        return;
      }
      report(batch);
      try (BufferedWriter writer =
          Files.newBufferedWriter(
              remainder,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING)) {
        while ((line = reader.readLine()) != null) {
          writer.write(line);
          writer.newLine();
          hasRemainder = true;
        }
      }
    }
    if (hasRemainder) move(remainder, sending);
    else {
      Files.deleteIfExists(remainder);
      Files.deleteIfExists(sending);
    }
  }

  private void report(List<TransferEvent> batch) {
    control.reportTransferEvents(
        batch.stream()
            .map(
                event -> {
                  try {
                    return mapper.writeValueAsString(event);
                  } catch (IOException exception) {
                    throw new IllegalStateException("Cannot serialize transfer event", exception);
                  }
                })
            .toList());
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
