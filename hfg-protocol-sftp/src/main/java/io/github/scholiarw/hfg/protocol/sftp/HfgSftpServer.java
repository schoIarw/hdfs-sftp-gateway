package io.github.scholiarw.hfg.protocol.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Iterator;
import java.util.List;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.util.io.resource.PathResource;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HfgSftpServer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(HfgSftpServer.class);
  private final SshServer server;

  public HfgSftpServer(
      Settings settings, HfgSftpAuthenticator authenticator, HfgSftpFileSystemAccessor files) {
    server = SshServer.setUpDefaultServer();
    server.setHost(settings.bindAddress());
    server.setPort(settings.port());
    var hostKeyProvider = new SimpleGeneratorHostKeyProvider(settings.hostKeyPath());
    String algorithm = hostKeyAlgorithm(settings.hostKeyPath(), settings.hostKeyAlgorithm());
    if (algorithm != null) hostKeyProvider.setAlgorithm(algorithm);
    server.setKeyPairProvider(hostKeyProvider);
    server.setPasswordAuthenticator(authenticator);
    server.setPublickeyAuthenticator(authenticator);
    server.addSessionListener(
        new SessionListener() {
          @Override
          public void sessionClosed(Session session) {
            if (session instanceof org.apache.sshd.server.session.ServerSession serverSession)
              authenticator.sessionClosed(serverSession);
          }
        });
    server.setFileSystemFactory(new VirtualFileSystemFactory(Path.of("/")));
    server.setSubsystemFactories(List.of(new HfgSftpSubsystemFactory(files)));
    server.setShellFactory(null);
    server.setCommandFactory(null);
  }

  /**
   * MINA only loads a host key whose algorithm matches the configured one; a mismatching file is
   * deleted and regenerated, which silently changes the server fingerprint. The algorithm is
   * therefore taken from the existing key when possible (overridable by configuration).
   */
  static String hostKeyAlgorithm(Path hostKey, String override) {
    if (override != null && !override.isBlank()) return override.trim();
    if (hostKey == null || !Files.isReadable(hostKey)) return null;
    try (InputStream input = Files.newInputStream(hostKey)) {
      Iterable<KeyPair> pairs =
          SecurityUtils.loadKeyPairIdentities(null, new PathResource(hostKey), input, null);
      Iterator<KeyPair> iterator = pairs == null ? null : pairs.iterator();
      if (iterator == null || !iterator.hasNext()) return null;
      String algorithm = iterator.next().getPublic().getAlgorithm();
      if ("ECDSA".equalsIgnoreCase(algorithm)) return "EC";
      log.info("Detected SFTP host key algorithm {} from {}", algorithm, hostKey);
      return algorithm;
    } catch (Exception e) {
      log.warn(
          "Cannot read SFTP host key {} ({}); MINA will try the configured/default algorithm and may replace the file",
          hostKey,
          e.getMessage());
      return null;
    }
  }

  public void start() throws IOException {
    server.start();
  }

  @Override
  public void close() throws IOException {
    server.stop(true);
  }

  public record Settings(String bindAddress, int port, Path hostKeyPath, String hostKeyAlgorithm) {}
}
