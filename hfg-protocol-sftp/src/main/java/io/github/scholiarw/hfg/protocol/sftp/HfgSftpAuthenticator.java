package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.contract.*;
import java.security.PublicKey;
import java.time.Clock;
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

  public HfgSftpAuthenticator(
      UserSnapshotProvider users, CredentialVerifier credentials, Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.clock = clock;
  }

  @Override
  public boolean authenticate(String username, String password, ServerSession session) {
    boolean accepted =
        users
            .findByUsername(username)
            .filter(u -> u.canLoginAt(clock.instant()))
            .filter(
                u -> u.passwordHash() != null && credentials.matches(password, u.passwordHash()))
            .isPresent();
    audit("password", username, session, accepted);
    return accepted;
  }

  @Override
  public boolean authenticate(String username, PublicKey key, ServerSession session) {
    String presented = keyMaterial(PublicKeyEntry.toString(key));
    boolean accepted =
        users
            .findByUsername(username)
            .filter(u -> u.canLoginAt(clock.instant()))
            .map(
                u ->
                    u.sshPublicKeys().stream()
                        .map(HfgSftpAuthenticator::keyMaterial)
                        .anyMatch(presented::equals))
            .orElse(false);
    audit("publickey", username, session, accepted);
    return accepted;
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
}
