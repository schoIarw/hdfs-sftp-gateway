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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class GatewayEventReporter implements TransferEventSink, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(GatewayEventReporter.class);
  private static final int BATCH_SIZE = 500;
  private static final int APPEND_BATCH_SIZE = 256;
  private static final int WAL_QUEUE_CAPACITY = 8192;
  private final ObjectMapper mapper;
  private final GatewayProperties properties;
  private final GrpcControlClient control;
  private final GatewayRuntimeStatus runtimeStatus;
  private final ReentrantLock appendLock = new ReentrantLock();
  private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(WAL_QUEUE_CAPACITY);
  private final Thread writer;
  private volatile boolean running = true;

  GatewayEventReporter(
      GatewayProperties properties,
      ObjectMapper mapper,
      GrpcControlClient control,
      GatewayRuntimeStatus runtimeStatus) {
    this.properties = properties;
    this.mapper = mapper;
    this.control = control;
    this.runtimeStatus = runtimeStatus;
    writer = new Thread(this::writeLoop, "hfg-event-wal");
    writer.setDaemon(true);
    writer.start();
  }

  @Override
  public void publish(TransferEvent event) {
    String line;
    try {
      line = mapper.writeValueAsString(event);
    } catch (IOException exception) {
      log.error("Cannot serialize transfer event", exception);
      return;
    }
    if (pending.offer(line)) return;
    // Preserve audit events when a sustained burst fills the bounded queue. Only the overflowing
    // caller pays the disk cost; normal transfer threads remain decoupled from WAL I/O.
    appendLines(List.of(line));
  }

  private void writeLoop() {
    List<String> batch = new ArrayList<>(APPEND_BATCH_SIZE);
    while (running || !pending.isEmpty() || !batch.isEmpty()) {
      try {
        if (batch.isEmpty()) {
          String first = pending.poll(1, TimeUnit.SECONDS);
          if (first == null) continue;
          batch.add(first);
          pending.drainTo(batch, APPEND_BATCH_SIZE - 1);
        }
        if (appendLines(batch)) batch.clear();
        else Thread.sleep(1000);
      } catch (InterruptedException interrupted) {
        if (running) log.warn("Transfer event WAL writer interrupted unexpectedly");
      } catch (RuntimeException failure) {
        log.error("Transfer event WAL writer failed", failure);
        batch.clear();
      }
    }
  }

  private boolean appendLines(List<String> lines) {
    if (lines.isEmpty()) return true;
    appendLock.lock();
    try {
      Path wal = properties.snapshot().eventWalPath();
      Files.createDirectories(wal.toAbsolutePath().getParent());
      try (BufferedWriter output =
          Files.newBufferedWriter(
              wal, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        for (String line : lines) {
          output.write(line);
          output.newLine();
        }
      }
      runtimeStatus.healthy("event-wal");
      return true;
    } catch (IOException exception) {
      runtimeStatus.failed("event-wal", exception);
      log.error("Cannot append transfer event WAL", exception);
      return false;
    } finally {
      appendLock.unlock();
    }
  }

  @Scheduled(fixedDelayString = "${hfg.snapshot.event-report-interval:PT5S}")
  void flush() {
    drainPending();
    Path wal = properties.snapshot().eventWalPath();
    Path sending = wal.resolveSibling(wal.getFileName() + ".sending");
    try {
      if (!prepareSending(wal, sending)) {
        // An empty queue means reporting is working (or has just caught up), so a failure recorded
        // during an earlier outage must not keep the gateway marked as degraded.
        runtimeStatus.healthy("event-reporting");
        return;
      }
      drainBatch(sending);
      runtimeStatus.healthy("event-reporting");
    } catch (io.grpc.StatusRuntimeException exception) {
      if (permanent(exception.getStatus().getCode())) {
        quarantine(sending, exception);
        runtimeStatus.healthy("event-reporting");
        return;
      }
      runtimeStatus.failed("event-reporting", exception);
      log.warn("Transfer event report failed; WAL retained: {}", exception.getMessage());
    } catch (Exception exception) {
      runtimeStatus.failed("event-reporting", exception);
      log.warn("Transfer event report failed; WAL retained: {}", exception.getMessage());
    }
  }

  private void drainPending() {
    List<String> batch = new ArrayList<>(APPEND_BATCH_SIZE);
    while (pending.drainTo(batch, APPEND_BATCH_SIZE) > 0) {
      if (!appendLines(batch)) {
        for (String line : batch) pending.offer(line);
        return;
      }
      batch.clear();
    }
  }

  @Override
  public void close() {
    running = false;
    writer.interrupt();
    try {
      writer.join(5000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    drainPending();
  }

  /**
   * A permanently rejected batch (bad request, revoked identity, unknown method) can never succeed,
   * so keeping it would block every later event behind it. Move it aside for inspection and let the
   * queue continue.
   */
  private static boolean permanent(io.grpc.Status.Code code) {
    return switch (code) {
      case INVALID_ARGUMENT, PERMISSION_DENIED, FAILED_PRECONDITION, NOT_FOUND, UNIMPLEMENTED ->
          true;
      default -> false;
    };
  }

  private void quarantine(Path sending, io.grpc.StatusRuntimeException exception) {
    Path rejected = sending.resolveSibling(sending.getFileName() + ".rejected");
    try {
      List<String> lines =
          Files.readAllLines(sending, StandardCharsets.UTF_8).stream()
              .filter(line -> !line.isBlank())
              .toList();
      try (BufferedWriter writer =
          Files.newBufferedWriter(
              rejected,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND)) {
        for (String line : lines) {
          writer.write(line);
          writer.newLine();
        }
      }
      long count = lines.size();
      Files.deleteIfExists(sending);
      log.warn(
          "Dropped {} permanently rejected transfer event(s) to {}: {}",
          count,
          rejected,
          exception.getMessage());
    } catch (Exception failure) {
      runtimeStatus.failed("event-reporting", failure);
      log.warn("Cannot quarantine rejected transfer events: {}", failure.getMessage());
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
