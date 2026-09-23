package io.github.scholiarw.hfg.traffic;

import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class ConcurrencyGate {
  private final Semaphore permits;
  private final boolean unlimited;

  public ConcurrencyGate(int maximum) {
    if (maximum < 0) throw new IllegalArgumentException("maximum must be non-negative");
    unlimited = maximum == 0;
    permits = new Semaphore(unlimited ? 1 : maximum, true);
  }

  public Lease acquire() {
    return acquire(Duration.ZERO);
  }

  /**
   * Waits for a bounded period so callers apply backpressure without creating an unbounded queue.
   */
  public Lease acquire(Duration timeout) {
    if (timeout == null || timeout.isNegative())
      throw new IllegalArgumentException("timeout must be non-negative");
    if (unlimited) return () -> {};
    boolean acquired;
    try {
      acquired = permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new HfgException(
          HfgErrorCode.RATE_LIMITED, "Interrupted while waiting for concurrency capacity");
    }
    if (!acquired) throw new HfgException(HfgErrorCode.RATE_LIMITED, "Concurrency limit reached");
    return new Lease() {
      private boolean closed;

      @Override
      public synchronized void close() {
        if (!closed) {
          closed = true;
          permits.release();
        }
      }
    };
  }

  public interface Lease extends AutoCloseable {
    @Override
    void close();
  }
}
