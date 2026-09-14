package io.github.scholiarw.hfg.traffic;

import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import java.util.concurrent.Semaphore;

public final class ConcurrencyGate {
  private final Semaphore permits;
  private final boolean unlimited;

  public ConcurrencyGate(int maximum) {
    if (maximum < 0) throw new IllegalArgumentException("maximum must be non-negative");
    unlimited = maximum == 0;
    permits = new Semaphore(unlimited ? 1 : maximum, true);
  }

  public Lease acquire() {
    if (unlimited) return () -> {};
    if (!permits.tryAcquire())
      throw new HfgException(HfgErrorCode.RATE_LIMITED, "Concurrency limit reached");
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
