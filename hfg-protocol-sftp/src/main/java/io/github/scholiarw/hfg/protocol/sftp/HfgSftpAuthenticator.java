package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.traffic.ConcurrencyGate;
import java.security.PublicKey;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HfgSftpAuthenticator implements PasswordAuthenticator, PublickeyAuthenticator {
  private static final Logger log = LoggerFactory.getLogger(HfgSftpAuthenticator.class);
  private final UserSnapshotProvider users;
  private final CredentialVerifier credentials;
  private final Clock clock;
  private final ConcurrencyGate globalConnections;
  private final ConcurrentHashMap<java.util.UUID, ConnectionState> connections =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ServerSession, ConcurrencyGate.Lease> sessions =
      new ConcurrentHashMap<>();

  public HfgSftpAuthenticator(
      UserSnapshotProvider users, CredentialVerifier credentials, Clock clock) {
    this(users, credentials, clock, 0);
  }

  public HfgSftpAuthenticator(
      UserSnapshotProvider users, CredentialVerifier credentials, Clock clock, int maxSessions) {
    this.users = users;
    this.credentials = credentials;
    this.clock = clock;
    this.globalConnections = new ConcurrencyGate(maxSessions);
  }

  @Override
  public boolean authenticate(String username, String password, ServerSession session) {
    UserSnapshot user =
        users
            .findByUsername(username)
            .filter(u -> u.canLoginAt(clock.instant()))
            .filter(
                u -> u.passwordHash() != null && credentials.matches(password, u.passwordHash()))
            .orElse(null);
    boolean accepted = user != null && admit(user, session);
    audit("password", username, session, accepted);
    return accepted;
  }

  @Override
  public boolean authenticate(String username, PublicKey key, ServerSession session) {
    String presented = keyMaterial(PublicKeyEntry.toString(key));
    UserSnapshot user =
        users
            .findByUsername(username)
            .filter(u -> u.canLoginAt(clock.instant()))
            .filter(
                u ->
                    u.sshPublicKeys().stream()
                        .map(HfgSftpAuthenticator::keyMaterial)
                        .anyMatch(presented::equals))
            .orElse(null);
    boolean accepted = user != null && admit(user, session);
    audit("publickey", username, session, accepted);
    return accepted;
  }

  void sessionClosed(ServerSession session) {
    ConcurrencyGate.Lease lease = sessions.remove(session);
    if (lease != null) lease.close();
  }

  private boolean admit(UserSnapshot user, ServerSession session) {
    if (session == null || sessions.containsKey(session)) return true;
    ConnectionState state =
        connections.compute(
            user.id(),
            (ignored, current) ->
                current != null && current.maximum == user.trafficPolicy().maxConnections()
                    ? current
                    : new ConnectionState(
                        user.trafficPolicy().maxConnections(),
                        new ConcurrencyGate(user.trafficPolicy().maxConnections())));
    try {
      ConcurrencyGate.Lease globalLease = globalConnections.acquire();
      ConcurrencyGate.Lease userLease;
      try {
        userLease = state.gate.acquire();
      } catch (RuntimeException failure) {
        globalLease.close();
        throw failure;
      }
      ConcurrencyGate.Lease lease =
          () -> {
            userLease.close();
            globalLease.close();
          };
      ConcurrencyGate.Lease raced = sessions.putIfAbsent(session, lease);
      if (raced != null) lease.close();
      return true;
    } catch (HfgException limit) {
      return false;
    }
  }

  /** One line per authentication attempt; MINA's own session logging stays at WARN. */
  private static void audit(
      String method, String username, ServerSession session, boolean accepted) {
    if (accepted)
      log.info("SFTP login user={} method={} from={}", username, method, remote(session));
    else
      log.warn("SFTP login rejected user={} method={} from={}", username, method, remote(session));
  }

  private static String remote(ServerSession session) {
    return session == null || session.getRemoteAddress() == null
        ? "-"
        : String.valueOf(session.getRemoteAddress());
  }

  static String keyMaterial(String line) {
    if (line == null) return "";
    String[] parts = line.trim().split("\\s+");
    return parts.length < 2 ? "" : parts[0] + " " + parts[1];
  }

  private record ConnectionState(int maximum, ConcurrencyGate gate) {}
}
