package io.github.scholiarw.hfg.protocol.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import io.github.scholiarw.hfg.contract.UserSnapshotProvider;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;

class HfgSftpAuthenticatorTest {
  @Test
  void ignoresAuthorizedKeyComments() {
    assertEquals(
        "ssh-ed25519 AAAATEST",
        HfgSftpAuthenticator.keyMaterial("ssh-ed25519 AAAATEST operator@example"));
    assertEquals("", HfgSftpAuthenticator.keyMaterial("invalid"));
  }

  @Test
  void authenticatesEveryUserFromTheSameSnapshotIndependently() {
    UserSnapshot first = user("first", "hash:first-password");
    UserSnapshot second = user("second", "hash:second-password");
    var authenticator =
        new HfgSftpAuthenticator(
            provider(first, second),
            (raw, encoded) -> ("hash:" + raw).equals(encoded),
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));

    assertTrue(authenticator.authenticate("first", "first-password", null));
    assertTrue(authenticator.authenticate("second", "second-password", null));
    assertFalse(authenticator.authenticate("second", "first-password", null));
  }

  @Test
  void limitsAndReleasesConcurrentSessionsPerUser() {
    UserSnapshot user = user("limited", "hash:password", 1);
    var authenticator =
        new HfgSftpAuthenticator(
            provider(user),
            (raw, encoded) -> ("hash:" + raw).equals(encoded),
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));
    ServerSession first = session("first");
    ServerSession second = session("second");

    assertTrue(authenticator.authenticate("limited", "password", first));
    assertFalse(authenticator.authenticate("limited", "password", second));
    authenticator.sessionClosed(first);
    assertTrue(authenticator.authenticate("limited", "password", second));
  }

  @Test
  void limitsAndReleasesSessionsAcrossUsers() {
    UserSnapshot firstUser = user("first", "hash:password");
    UserSnapshot secondUser = user("second", "hash:password");
    var authenticator =
        new HfgSftpAuthenticator(
            provider(firstUser, secondUser),
            (raw, encoded) -> ("hash:" + raw).equals(encoded),
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC),
            1);
    ServerSession first = session("first");
    ServerSession second = session("second");

    assertTrue(authenticator.authenticate("first", "password", first));
    assertFalse(authenticator.authenticate("second", "password", second));
    authenticator.sessionClosed(first);
    assertTrue(authenticator.authenticate("second", "password", second));
  }

  private static UserSnapshot user(String username, String passwordHash) {
    return user(username, passwordHash, 0);
  }

  private static UserSnapshot user(String username, String passwordHash, int maximumConnections) {
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
        new TrafficPolicy(0, 0, maximumConnections));
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

  private static ServerSession session(String name) {
    return (ServerSession)
        Proxy.newProxyInstance(
            ServerSession.class.getClassLoader(),
            new Class<?>[] {ServerSession.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("toString")) return name;
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              if (method.getName().equals("equals")) return proxy == arguments[0];
              Class<?> type = method.getReturnType();
              if (!type.isPrimitive()) return null;
              if (type == boolean.class) return false;
              if (type == char.class) return '\0';
              return 0;
            });
  }
}
