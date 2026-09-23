package io.github.scholiarw.hfg.protocol.ftp;

import java.util.Objects;
import org.apache.ftpserver.*;
import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;

public final class HfgFtpServer implements AutoCloseable {
  private final FtpServer server;

  public HfgFtpServer(Settings settings, UserManager users, FileSystemFactory files) {
    var data = new DataConnectionConfigurationFactory();
    data.setPassivePorts(settings.passivePorts());
    data.setPassiveExternalAddress(settings.passiveExternalAddress());
    data.setActiveEnabled(settings.activeModeEnabled());
    var listener = new ListenerFactory();
    listener.setServerAddress(settings.bindAddress());
    listener.setPort(settings.port());
    listener.setIdleTimeout(settings.idleTimeoutSeconds());
    listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
    var factory = new FtpServerFactory();
    var connections = new ConnectionConfigFactory();
    connections.setAnonymousLoginEnabled(false);
    connections.setMaxAnonymousLogins(0);
    connections.setMaxLogins(settings.maxLogins());
    connections.setMaxThreads(settings.workerThreads());
    factory.setConnectionConfig(connections.createConnectionConfig());
    factory.setUserManager(users);
    factory.setFileSystem(files);
    factory.addListener("default", listener.createListener());
    this.server = factory.createServer();
  }

  public void start() throws Exception {
    server.start();
  }

  @Override
  public void close() {
    if (!server.isStopped()) server.stop();
  }

  public record Settings(
      String bindAddress,
      int port,
      String passivePorts,
      String passiveExternalAddress,
      boolean activeModeEnabled,
      int idleTimeoutSeconds,
      int maxLogins,
      int workerThreads) {
    public Settings {
      Objects.requireNonNull(bindAddress);
      Objects.requireNonNull(passivePorts);
      if (maxLogins < 1 || workerThreads < 1)
        throw new IllegalArgumentException("FTP maxLogins and workerThreads must be positive");
    }
  }
}
