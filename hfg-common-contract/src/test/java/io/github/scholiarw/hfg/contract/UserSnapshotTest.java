package io.github.scholiarw.hfg.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserSnapshotTest {
  @Test
  void rejectsExpiredAndDisabledUsers() {
    var expired =
        new UserSnapshot(
            UUID.randomUUID(),
            "u",
            "x",
            Set.of(),
            "d",
            "b",
            "g",
            "u",
            AccountStatus.ENABLED,
            Instant.now().minusSeconds(1),
            List.of(),
            null);
    assertFalse(expired.canLoginAt(Instant.now()));
    var disabled =
        new UserSnapshot(
            UUID.randomUUID(),
            "u",
            "x",
            Set.of(),
            "d",
            "b",
            "g",
            "u",
            AccountStatus.DISABLED,
            null,
            List.of(),
            null);
    assertFalse(disabled.canLoginAt(Instant.now()));
  }
}
