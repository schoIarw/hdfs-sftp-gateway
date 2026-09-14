package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import io.github.scholiarw.hfg.traffic.ConcurrencyGate;
import io.github.scholiarw.hfg.traffic.NanoClock;
import io.github.scholiarw.hfg.traffic.TokenBucket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalTransferLimiter implements TransferLimiter {
  private static final long QUANTUM = 64L * 1024 * 1024;
  private static final long RENEW_INTERVAL_NANOS = java.time.Duration.ofMinutes(1).toNanos();
  private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap<>();
  private final QuotaLeaseClient quotas;

  public LocalTransferLimiter() {
    this(QuotaLeaseClient.noop());
  }

  public LocalTransferLimiter(QuotaLeaseClient quotas) {
    this.quotas = quotas;
  }

  @Override
  public Permit open(UserSnapshot user, TransferDirection direction) {
    var key = new Key(user.id(), direction);
    State state =
        states.compute(
            key,
            (ignored, current) ->
                current != null && current.policy.equals(user.trafficPolicy())
                    ? current
                    : create(user, direction));
    var lease = state.gate.acquire();
    boolean fileQuota =
        direction == TransferDirection.UPLOAD
            ? user.trafficPolicy().periodUploadFiles() > 0
            : user.trafficPolicy().periodDownloadFiles() > 0;
    boolean byteQuota =
        direction == TransferDirection.UPLOAD
            ? user.trafficPolicy().periodUploadBytes() > 0
            : user.trafficPolicy().periodDownloadBytes() > 0;
    var quotaLeases = new java.util.ArrayList<QuotaLeaseClient.Lease>();
    if (fileQuota) quotaLeases.add(quotas.reserve(user, direction, 1, 0));
    return new Permit() {
      private long used, reserved;
      private long lastRenewed = System.nanoTime();
      private boolean closed;

      public void acquire(int bytes) {
        state.bucket.acquire(bytes);
        if (byteQuota)
          while (reserved - used < bytes) {
            long amount = Math.max(QUANTUM, bytes - (reserved - used));
            quotaLeases.add(quotas.reserve(user, direction, 0, amount));
            reserved += amount;
          }
        long now = System.nanoTime();
        if (!quotaLeases.isEmpty() && now - lastRenewed >= RENEW_INTERVAL_NANOS) {
          for (var quotaLease : quotaLeases) quotas.renew(quotaLease);
          lastRenewed = now;
        }
        used += bytes;
      }

      public synchronized void complete(boolean success) {
        if (closed) return;
        closed = true;
        long remaining = success ? used : 0;
        RuntimeException failure = null;
        try {
          for (var quotaLease : quotaLeases) {
            long files = quotaLease.files() > 0 && success ? 1 : 0;
            long consumed = Math.min(remaining, quotaLease.bytes());
            try {
              quotas.commit(quotaLease, files, consumed);
            } catch (RuntimeException exception) {
              if (failure == null) failure = exception;
              else failure.addSuppressed(exception);
            }
            remaining -= consumed;
          }
        } finally {
          lease.close();
        }
        if (failure != null) throw failure;
      }
    };
  }

  private static State create(UserSnapshot user, TransferDirection direction) {
    var p = user.trafficPolicy();
    long rate =
        direction == TransferDirection.UPLOAD
            ? p.uploadBytesPerSecond()
            : p.downloadBytesPerSecond();
    long burst =
        direction == TransferDirection.UPLOAD ? p.uploadBurstBytes() : p.downloadBurstBytes();
    int concurrent =
        direction == TransferDirection.UPLOAD ? p.maxUploadTransfers() : p.maxDownloadTransfers();
    return new State(
        new TokenBucket(rate, burst, NanoClock.system()), new ConcurrencyGate(concurrent), p);
  }

  private record Key(UUID userId, TransferDirection direction) {}

  private record State(
      TokenBucket bucket,
      ConcurrencyGate gate,
      io.github.scholiarw.hfg.contract.TrafficPolicy policy) {}
}
