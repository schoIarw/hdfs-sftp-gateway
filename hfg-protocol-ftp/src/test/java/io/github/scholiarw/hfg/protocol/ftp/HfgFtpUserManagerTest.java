package io.github.scholiarw.hfg.protocol.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import io.github.scholiarw.hfg.contract.UserSnapshotProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.junit.jupiter.api.Test;

class HfgFtpUserManagerTest {
  @Test
  void authenticatesEveryUserFromTheSameSnapshotIndependently() throws Exception {
    UserSnapshot first = user("first", "hash:first-password");
    UserSnapshot second = user("second", "hash:second-password");
    var manager =
        new HfgFtpUserManager(
            provider(first, second),
            (raw, encoded) -> ("hash:" + raw).equals(encoded),
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));

    assertEquals(
        "first",
        manager
            .authenticate(new UsernamePasswordAuthentication("first", "first-password"))
            .getName());
    assertEquals(
        "second",
        manager
            .authenticate(new UsernamePasswordAuthentication("second", "second-password"))
            .getName());
    assertThrows(
        AuthenticationFailedException.class,
        () ->
            manager.authenticate(
                new UsernamePasswordAuthentication("second", "first-password")));
  }

  private static UserSnapshot user(String username, String passwordHash) {
    return new UserSnapshot(
        UUID.randomUUID(),
        username,
        passwordHash,
        Set.of(),
        null,
        null,
        "group",
        AccountStatus.ENABLED,
        null,
        List.of(),
        TrafficPolicy.unlimited());
  }

  private static UserSnapshotProvider provider(UserSnapshot... users) {
    Map<String, UserSnapshot> values =
        java.util.Arrays.stream(users)
            .collect(java.util.stream.Collectors.toMap(UserSnapshot::username, user -> user));
    return new UserSnapshotProvider() {
      @Override
      public Optional<UserSnapshot> findByUsername(String username) {
        return Optional.ofNullable(values.get(username));
      }

      @Override
      public Collection<UserSnapshot> allUsers() {
        return values.values();
      }
    };
  }
}
