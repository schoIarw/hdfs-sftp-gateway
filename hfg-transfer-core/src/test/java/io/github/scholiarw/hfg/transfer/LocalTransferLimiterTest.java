package io.github.scholiarw.hfg.transfer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalTransferLimiterTest {
  @Test
  void releasesConcurrencyPermitWhenInitialQuotaReservationFails() {
    AtomicBoolean firstReservation = new AtomicBoolean(true);
    QuotaLeaseClient quotas =
        new QuotaLeaseClient() {
          public Lease reserve(
              UserSnapshot user, TransferDirection direction, long files, long bytes) {
            if (firstReservation.getAndSet(false)) throw new IllegalStateException("quota down");
            return new Lease(UUID.randomUUID(), files, bytes);
          }

          public void commit(Lease lease, long completedFiles, long completedBytes) {}
        };
    var limiter = new LocalTransferLimiter(quotas);
    UserSnapshot user =
        user(new TrafficPolicy(0, 0, 0, 0, 0, 1, 1, 10, 0, 0, 0, TrafficPolicy.Period.DAY, "UTC"));

    assertThrows(
        IllegalStateException.class, () -> limiter.open(user, TransferDirection.UPLOAD, 0));
    var next = assertDoesNotThrow(() -> limiter.open(user, TransferDirection.UPLOAD, 0));
    assertDoesNotThrow(() -> next.complete(false));
  }

  @Test
  void usesTheBytesActuallyGrantedByManager() {
    AtomicInteger reservations = new AtomicInteger();
    AtomicLong committed = new AtomicLong();
    QuotaLeaseClient quotas =
        new QuotaLeaseClient() {
          public Lease reserve(
              UserSnapshot user, TransferDirection direction, long files, long bytes) {
            int call = reservations.incrementAndGet();
            if (call > 1) throw new IllegalStateException("quota exhausted");
            return new Lease(UUID.randomUUID(), files, Math.min(bytes, 10));
          }

          public void commit(Lease lease, long completedFiles, long completedBytes) {
            committed.addAndGet(completedBytes);
          }
        };
    var limiter = new LocalTransferLimiter(quotas);
    UserSnapshot user =
        user(new TrafficPolicy(0, 0, 0, 0, 0, 1, 1, 0, 0, 10, 0, TrafficPolicy.Period.DAY, "UTC"));
    var permit = limiter.open(user, TransferDirection.UPLOAD, 0);

    permit.acquire(4);
    permit.acquire(6);
    assertEquals(1, reservations.get());
    assertThrows(IllegalStateException.class, () -> permit.acquire(1));
    permit.complete(true);
    assertEquals(10, committed.get());
  }

  @Test
  void includesStagedBytesWhenAResumedUploadCompletes() {
    AtomicLong committed = new AtomicLong();
    QuotaLeaseClient quotas =
        new QuotaLeaseClient() {
          public Lease reserve(
              UserSnapshot user, TransferDirection direction, long files, long bytes) {
            return new Lease(UUID.randomUUID(), files, bytes);
          }

          public void commit(Lease lease, long completedFiles, long completedBytes) {
            committed.addAndGet(completedBytes);
          }
        };
    UserSnapshot user =
        user(new TrafficPolicy(0, 0, 0, 0, 0, 1, 1, 0, 0, 100, 0, TrafficPolicy.Period.DAY, "UTC"));
    var permit = new LocalTransferLimiter(quotas).open(user, TransferDirection.UPLOAD, 3);

    permit.acquire(2);
    permit.complete(true);

    assertEquals(5, committed.get());
  }

  @Test
  void releasesConcurrencyPermitWhenQuotaCommitFails() {
    AtomicBoolean firstCommit = new AtomicBoolean(true);
    QuotaLeaseClient quotas =
        new QuotaLeaseClient() {
          @Override
          public Lease reserve(
              UserSnapshot user, TransferDirection direction, long files, long bytes) {
            return new Lease(UUID.randomUUID(), files, bytes);
          }

          @Override
          public void commit(Lease lease, long completedFiles, long completedBytes) {
            if (firstCommit.getAndSet(false))
              throw new IllegalStateException("manager unavailable");
          }
        };
    var policy =
        new TrafficPolicy(0, 0, 0, 0, 0, 1, 1, 10, 0, 0, 0, TrafficPolicy.Period.DAY, "UTC");
    var user =
        new UserSnapshot(
            UUID.randomUUID(),
            "user_01",
            "hash",
            Set.of(),
            null,
            null,
            "group",
            AccountStatus.ENABLED,
            null,
            List.of(),
            policy);
    var limiter = new LocalTransferLimiter(quotas);

    var first = limiter.open(user, TransferDirection.UPLOAD);
    assertThrows(IllegalStateException.class, () -> first.complete(true));

    var second = assertDoesNotThrow(() -> limiter.open(user, TransferDirection.UPLOAD));
    assertDoesNotThrow(() -> second.complete(false));
  }

  private static UserSnapshot user(TrafficPolicy policy) {
    return new UserSnapshot(
        UUID.randomUUID(),
        "user_01",
        "hash",
        Set.of(),
        null,
        null,
        "group",
        AccountStatus.ENABLED,
        null,
        List.of(),
        policy);
  }
}
