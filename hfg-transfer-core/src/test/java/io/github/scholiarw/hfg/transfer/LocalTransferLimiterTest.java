package io.github.scholiarw.hfg.transfer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LocalTransferLimiterTest {
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
            "hdfs-user",
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
}
