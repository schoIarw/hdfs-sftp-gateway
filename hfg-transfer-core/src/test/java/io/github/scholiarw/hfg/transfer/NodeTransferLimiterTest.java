package io.github.scholiarw.hfg.transfer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeTransferLimiterTest {
  @Test
  void rejectsBeyondNodeLimitAndReleasesExactlyOnce() {
    var limiter = new NodeTransferLimiter(TransferLimiter.unlimited(), 1, 1, 1, 0, Duration.ZERO);
    var first = limiter.open(user(), TransferDirection.UPLOAD);

    HfgException rejected =
        assertThrows(HfgException.class, () -> limiter.open(user(), TransferDirection.DOWNLOAD));
    assertEquals(HfgErrorCode.RATE_LIMITED, rejected.code());
    assertEquals(1, limiter.activeTotal());
    assertEquals(1, limiter.rejected());

    first.complete(true);
    first.complete(false);
    assertEquals(0, limiter.activeTotal());
    assertDoesNotThrow(() -> limiter.open(user(), TransferDirection.DOWNLOAD).complete(true));
  }

  @Test
  void releasesTotalPermitWhenDirectionLimitRejects() {
    var limiter = new NodeTransferLimiter(TransferLimiter.unlimited(), 2, 1, 1, 0, Duration.ZERO);
    var upload = limiter.open(user(), TransferDirection.UPLOAD);

    assertThrows(HfgException.class, () -> limiter.open(user(), TransferDirection.UPLOAD));
    var download = assertDoesNotThrow(() -> limiter.open(user(), TransferDirection.DOWNLOAD));
    assertEquals(2, limiter.activeTotal());

    upload.complete(true);
    download.complete(true);
  }

  @Test
  void capsTransfersPerUserWhenConfigured() {
    var limiter =
        new NodeTransferLimiter(TransferLimiter.unlimited(), 100, 100, 100, 2, Duration.ZERO);
    UserSnapshot firstUser = user();
    UserSnapshot secondUser = user();
    var firstUpload = limiter.open(firstUser, TransferDirection.UPLOAD);
    var firstDownload = limiter.open(firstUser, TransferDirection.DOWNLOAD);

    // The first user has consumed its per-user quota (2) while the node is far from saturated.
    HfgException rejected =
        assertThrows(HfgException.class, () -> limiter.open(firstUser, TransferDirection.UPLOAD));
    assertEquals(HfgErrorCode.RATE_LIMITED, rejected.code());
    assertEquals(1, limiter.rejected());

    // Another user is still admitted by the per-user gate.
    assertDoesNotThrow(() -> limiter.open(secondUser, TransferDirection.UPLOAD).complete(true));

    // Completing one transfer frees the slot for the same user again.
    firstUpload.complete(true);
    assertDoesNotThrow(() -> limiter.open(firstUser, TransferDirection.UPLOAD).complete(true));
    firstDownload.complete(true);
    assertEquals(0, limiter.activeTotal());
  }

  @Test
  void perUserCapDoesNotLeakAcrossCompletion() {
    var limiter =
        new NodeTransferLimiter(TransferLimiter.unlimited(), 100, 100, 100, 1, Duration.ZERO);
    UserSnapshot user = user();
    var first = limiter.open(user, TransferDirection.UPLOAD);
    first.complete(true);

    assertDoesNotThrow(() -> limiter.open(user, TransferDirection.UPLOAD).complete(true));
    assertEquals(0, limiter.rejected());
  }

  private static UserSnapshot user() {
    UUID id = UUID.randomUUID();
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
        TrafficPolicy.unlimited());
  }
}