package io.github.scholiarw.hfg.transfer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalTransferLimiterTest {
  @Test
  void leavesConnectionConcurrencyToTheProtocolSession() {
    UserSnapshot user = user(UUID.randomUUID(), new TrafficPolicy(0, 0, 1));
    var limiter = new LocalTransferLimiter();
    var upload = limiter.open(user, TransferDirection.UPLOAD);
    var download = assertDoesNotThrow(() -> limiter.open(user, TransferDirection.DOWNLOAD));

    upload.complete(true);
    assertDoesNotThrow(() -> download.complete(true));
  }

  @Test
  void maintainsIndependentRateStateForEachUser() {
    var limiter = new LocalTransferLimiter();
    UserSnapshot first = user(UUID.randomUUID(), new TrafficPolicy(0, 0, 1));
    UserSnapshot second = user(UUID.randomUUID(), new TrafficPolicy(0, 0, 1));

    var firstPermit = limiter.open(first, TransferDirection.UPLOAD);
    var secondPermit = assertDoesNotThrow(() -> limiter.open(second, TransferDirection.UPLOAD));

    firstPermit.complete(false);
    secondPermit.complete(false);
  }

  @Test
  void completeIsIdempotentAndReleasesExactlyOnce() {
    var limiter = new LocalTransferLimiter();
    UserSnapshot user = user(UUID.randomUUID(), new TrafficPolicy(0, 0, 1));
    var permit = limiter.open(user, TransferDirection.UPLOAD, 128);

    permit.complete(true);
    permit.complete(false);

    var next = assertDoesNotThrow(() -> limiter.open(user, TransferDirection.DOWNLOAD));
    next.complete(true);
  }

  private static UserSnapshot user(UUID id, TrafficPolicy policy) {
    return new UserSnapshot(
        id,
        "user-" + id,
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
