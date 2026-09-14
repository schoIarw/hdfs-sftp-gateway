package io.github.scholiarw.hfg.protocol.ftp;

import io.github.scholiarw.hfg.contract.*;
import java.time.Clock;
import java.util.List;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.usermanager.AnonymousAuthentication;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

public final class HfgFtpUserManager implements UserManager {
  private final UserSnapshotProvider users;
  private final CredentialVerifier credentials;
  private final Clock clock;

  public HfgFtpUserManager(
      UserSnapshotProvider users, CredentialVerifier credentials, Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.clock = clock;
  }

  @Override
  public User getUserByName(String username) {
    return users
        .findByUsername(username)
        .filter(u -> u.canLoginAt(clock.instant()))
        .map(this::toFtpUser)
        .orElse(null);
  }

  @Override
  public String[] getAllUserNames() {
    return users.allUsers().stream().map(UserSnapshot::username).toArray(String[]::new);
  }

  @Override
  public void delete(String username) throws FtpException {
    throw readOnly();
  }

  @Override
  public void save(User user) throws FtpException {
    throw readOnly();
  }

  @Override
  public boolean doesExist(String username) {
    return users.findByUsername(username).isPresent();
  }

  @Override
  public User authenticate(Authentication authentication) throws AuthenticationFailedException {
    if (authentication instanceof AnonymousAuthentication)
      throw new AuthenticationFailedException("Anonymous access is disabled");
    if (!(authentication instanceof UsernamePasswordAuthentication login))
      throw new AuthenticationFailedException("Unsupported authentication");
    UserSnapshot snapshot =
        users
            .findByUsername(login.getUsername())
            .filter(u -> u.canLoginAt(clock.instant()))
            .orElseThrow(() -> new AuthenticationFailedException("Invalid credentials"));
    if (snapshot.passwordHash() == null
        || !credentials.matches(login.getPassword(), snapshot.passwordHash())) {
      throw new AuthenticationFailedException("Invalid credentials");
    }
    return toFtpUser(snapshot);
  }

  @Override
  public String getAdminName() {
    return "";
  }

  @Override
  public boolean isAdmin(String username) {
    return false;
  }

  private User toFtpUser(UserSnapshot snapshot) {
    var user = new BaseUser();
    user.setName(snapshot.username());
    user.setEnabled(true);
    user.setHomeDirectory("/");
    user.setMaxIdleTime(300);
    int maxConnections = snapshot.trafficPolicy().maxConnections();
    user.setAuthorities(
        List.of(
            new WritePermission(),
            new ConcurrentLoginPermission(maxConnections == 0 ? 0 : maxConnections, 0)));
    return user;
  }

  private static FtpException readOnly() {
    return new FtpException("Users are managed by HFG Manager");
  }
}
