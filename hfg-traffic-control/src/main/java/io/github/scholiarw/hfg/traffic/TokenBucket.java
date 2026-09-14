package io.github.scholiarw.hfg.traffic;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

public final class TokenBucket {
  private final long bytesPerSecond;
  private final long capacity;
  private final NanoClock clock;
  private double available;
  private long lastRefill;

  public TokenBucket(long bytesPerSecond, long burstBytes, NanoClock clock) {
    if (bytesPerSecond < 0 || burstBytes < 0)
      throw new IllegalArgumentException("rate and burst must be non-negative");
    this.bytesPerSecond = bytesPerSecond;
    this.capacity = bytesPerSecond == 0 ? Long.MAX_VALUE : Math.max(burstBytes, bytesPerSecond);
    this.available = capacity;
    this.clock = clock;
    this.lastRefill = clock.nanoTime();
  }

  public void acquire(int bytes) {
    if (bytes <= 0 || bytesPerSecond == 0) return;
    int remaining = bytes;
    while (remaining > 0) {
      int chunk = (int) Math.min(remaining, Math.min(capacity, Integer.MAX_VALUE));
      acquireChunk(chunk);
      remaining -= chunk;
    }
  }

  private void acquireChunk(int bytes) {
    while (true) {
      long waitNanos;
      synchronized (this) {
        refill();
        if (available >= bytes) {
          available -= bytes;
          return;
        }
        waitNanos = (long) Math.ceil((bytes - available) * 1_000_000_000D / bytesPerSecond);
      }
      LockSupport.parkNanos(Math.min(waitNanos, Duration.ofMillis(250).toNanos()));
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while rate limited");
      }
    }
  }

  synchronized boolean tryAcquire(int bytes) {
    if (bytes <= 0 || bytesPerSecond == 0) return true;
    refill();
    if (available < bytes) return false;
    available -= bytes;
    return true;
  }

  private void refill() {
    long now = clock.nanoTime();
    long elapsed = Math.max(0, now - lastRefill);
    available = Math.min(capacity, available + elapsed * bytesPerSecond / 1_000_000_000D);
    lastRefill = now;
  }
}
