package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import io.github.scholiarw.hfg.traffic.NanoClock;
import io.github.scholiarw.hfg.traffic.TokenBucket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies per-user upload and download rate limits; connection limits live at protocol sessions.
 */
public final class LocalTransferLimiter implements TransferLimiter {
  private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

  @Override
  public Permit open(UserSnapshot user, TransferDirection direction, long initialBytes) {
    if (initialBytes < 0) throw new IllegalArgumentException("initialBytes must be non-negative");
    State state =
        states.compute(
            user.id(),
            (ignored, current) ->
                current != null && current.policy.equals(user.trafficPolicy())
                    ? current
                    : create(user.trafficPolicy()));
    TokenBucket rate =
        direction == TransferDirection.UPLOAD ? state.uploadRate : state.downloadRate;
    return new Permit() {
      private boolean closed;

      @Override
      public void acquire(int bytes) {
        if (bytes > 0) rate.acquire(bytes);
      }

      @Override
      public synchronized void complete(boolean success) {
        if (closed) return;
        closed = true;
      }
    };
  }

  private static State create(TrafficPolicy policy) {
    return new State(
        new TokenBucket(
            policy.uploadBytesPerSecond(), policy.uploadBytesPerSecond(), NanoClock.system()),
        new TokenBucket(
            policy.downloadBytesPerSecond(), policy.downloadBytesPerSecond(), NanoClock.system()),
        policy);
  }

  private record State(TokenBucket uploadRate, TokenBucket downloadRate, TrafficPolicy policy) {}
}
