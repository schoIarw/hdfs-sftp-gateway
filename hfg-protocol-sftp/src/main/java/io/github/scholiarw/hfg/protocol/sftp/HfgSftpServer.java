package io.github.scholiarw.hfg.protocol.sftp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

public final class HfgSftpServer implements AutoCloseable {
  private final SshServer server;

  public HfgSftpServer(
      Settings settings, HfgSftpAuthenticator authenticator, HfgSftpFileSystemAccessor files) {
    server = SshServer.setUpDefaultServer();
    server.setHost(settings.bindAddress());
    server.setPort(settings.port());
    server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(settings.hostKeyPath()));
    server.setPasswordAuthenticator(authenticator);
    server.setPublickeyAuthenticator(authenticator);
    server.setFileSystemFactory(new VirtualFileSystemFactory(Path.of("/")));
    server.setSubsystemFactories(
        List.of(new SftpSubsystemFactory.Builder().withFileSystemAccessor(files).build()));
    server.setShellFactory(null);
    server.setCommandFactory(null);
  }

  public void start() throws IOException {
    server.start();
  }

  @Override
  public void close() throws IOException {
    server.stop(true);
  }

  public record Settings(String bindAddress, int port, Path hostKeyPath) {}
}
