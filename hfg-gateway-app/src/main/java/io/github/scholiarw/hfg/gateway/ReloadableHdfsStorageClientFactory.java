package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.storage.StorageClient;
import io.github.scholiarw.hfg.storage.StorageClientFactory;
import io.github.scholiarw.hfg.storage.hdfs.HdfsStorageClientFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ReloadableHdfsStorageClientFactory implements StorageClientFactory {
  private static final Logger log =
      LoggerFactory.getLogger(ReloadableHdfsStorageClientFactory.class);

  /**
   * Grace period before the replaced HDFS client pool is closed. In-flight transfers obtained their
   * {@link StorageClient} (and its underlying {@code FileSystem}) from the old pool at open time;
   * closing it immediately would fail every ongoing read/write with "Filesystem closed". The upload
   * path is staging + atomic rename, so no partial file appears, but the transfer still aborts.
   * Waiting out the grace lets transfers that started before the swap finish on the old pool. Note
   * that a transfer holding a pooled client for longer than the grace period still races the close;
   * this turns a guaranteed outage on every config change into a rare tail risk.
   */
  private static final Duration CLOSE_GRACE = Duration.ofMinutes(5);

  private final AtomicReference<HdfsStorageClientFactory> delegate = new AtomicReference<>();
  private final ScheduledExecutorService closer =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hfg-hdfs-factory-closer");
            thread.setDaemon(true);
            return thread;
          });

  void install(String defaultFs, List<String> resources, String principal, String keytab)
      throws IOException {
    HdfsStorageClientFactory previous =
        delegate.getAndSet(
            new HdfsStorageClientFactory(
                new HdfsStorageClientFactory.Settings(
                    defaultFs, resources, principal, keytab, false)));
    if (previous != null) scheduleClose(previous);
  }

  private void scheduleClose(HdfsStorageClientFactory previous) {
    log.info(
        "HDFS configuration replaced; closing the previous client pool after a {} grace period so "
            + "in-flight transfers can finish",
        CLOSE_GRACE);
    closer.schedule(
        () -> {
          log.info("Grace period elapsed; closing the replaced HDFS client pool");
          closeQuietly(previous);
        },
        CLOSE_GRACE.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  boolean ready() {
    return delegate.get() != null;
  }

  @Override
  public StorageClient forEffectiveUser(String ignored) throws IOException {
    HdfsStorageClientFactory current = delegate.get();
    if (current == null) throw new IOException("HDFS configuration has not been received");
    return current.forEffectiveUser(null);
  }

  /** Shuts down the close scheduler and closes the current pool; called by Spring on shutdown. */
  public void close() {
    closer.shutdownNow();
    HdfsStorageClientFactory current = delegate.getAndSet(null);
    if (current != null) closeQuietly(current);
  }

  private static void closeQuietly(HdfsStorageClientFactory factory) {
    try {
      factory.close();
    } catch (RuntimeException failure) {
      log.warn("Failed to close the replaced HDFS client pool", failure);
    }
  }
}
