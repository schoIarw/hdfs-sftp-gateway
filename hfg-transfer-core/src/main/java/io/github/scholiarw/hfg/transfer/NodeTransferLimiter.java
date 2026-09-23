package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.HfgException;
import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import io.github.scholiarw.hfg.traffic.ConcurrencyGate;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounds transfer work for one gateway node while delegating per-user rate limiting.
 *
 * <p>The total gate is acquired before the direction gate and both share one timeout budget. This
 * prevents a burst from creating unbounded protocol/HDFS work while still allowing short bursts to
 * wait for capacity instead of failing immediately.
 */
public final class NodeTransferLimiter implements TransferLimiter {
  private final TransferLimiter delegate;
  private final ConcurrencyGate total;
  private final ConcurrencyGate uploads;
  private final ConcurrencyGate downloads;
  private final Duration acquireTimeout;
  private final AtomicInteger waiting = new AtomicInteger();
  private final AtomicInteger activeUploads = new AtomicInteger();
  private final AtomicInteger activeDownloads = new AtomicInteger();
  private final LongAdder rejected = new LongAdder();

  public NodeTransferLimiter(
      TransferLimiter delegate,
      int maxActiveTransfers,
      int maxUploads,
      int maxDownloads,
      Duration acquireTimeout) {
    this.delegate = Objects.requireNonNull(delegate);
    if (maxActiveTransfers < 1 || maxUploads < 1 || maxDownloads < 1)
      throw new IllegalArgumentException("node transfer limits must be positive");
    this.total = new ConcurrencyGate(maxActiveTransfers);
    this.uploads = new ConcurrencyGate(maxUploads);
    this.downloads = new ConcurrencyGate(maxDownloads);
    if (acquireTimeout == null || acquireTimeout.isNegative())
      throw new IllegalArgumentException("acquireTimeout must be non-negative");
    this.acquireTimeout = acquireTimeout;
  }

  @Override
  public Permit open(UserSnapshot user, TransferDirection direction, long initialBytes) {
    Objects.requireNonNull(direction);
    waiting.incrementAndGet();
    long deadline = deadline(acquireTimeout);
    ConcurrencyGate.Lease totalLease = null;
    ConcurrencyGate.Lease directionLease = null;
    try {
      totalLease = total.acquire(remaining(deadline));
      directionLease = gate(direction).acquire(remaining(deadline));
      Permit delegated = delegate.open(user, direction, initialBytes);
      activeCounter(direction).incrementAndGet();
      return managed(delegated, direction, totalLease, directionLease);
    } catch (HfgException rejectedByCapacity) {
      rejected.increment();
      close(directionLease);
      close(totalLease);
      throw rejectedByCapacity;
    } catch (RuntimeException failure) {
      close(directionLease);
      close(totalLease);
      throw failure;
    } finally {
      waiting.decrementAndGet();
    }
  }

  private Permit managed(
      Permit delegated,
      TransferDirection direction,
      ConcurrencyGate.Lease totalLease,
      ConcurrencyGate.Lease directionLease) {
    return new Permit() {
      private boolean closed;

      @Override
      public void acquire(int bytes) {
        delegated.acquire(bytes);
      }

      @Override
      public synchronized void complete(boolean success) {
        if (closed) return;
        closed = true;
        try {
          delegated.complete(success);
        } finally {
          activeCounter(direction).decrementAndGet();
          directionLease.close();
          totalLease.close();
        }
      }
    };
  }

  public int activeTotal() {
    return activeUploads.get() + activeDownloads.get();
  }

  public int active(TransferDirection direction) {
    return activeCounter(direction).get();
  }

  public int waiting() {
    return waiting.get();
  }

  public long rejected() {
    return rejected.sum();
  }

  private ConcurrencyGate gate(TransferDirection direction) {
    return direction == TransferDirection.UPLOAD ? uploads : downloads;
  }

  private AtomicInteger activeCounter(TransferDirection direction) {
    return direction == TransferDirection.UPLOAD ? activeUploads : activeDownloads;
  }

  private static long deadline(Duration timeout) {
    long now = System.nanoTime();
    long nanos = timeout.toNanos();
    long deadline = now + nanos;
    return deadline < now ? Long.MAX_VALUE : deadline;
  }

  private static Duration remaining(long deadline) {
    if (deadline == Long.MAX_VALUE) return Duration.ofNanos(Long.MAX_VALUE);
    return Duration.ofNanos(Math.max(0L, deadline - System.nanoTime()));
  }

  private static void close(ConcurrencyGate.Lease lease) {
    if (lease != null) lease.close();
  }
}
