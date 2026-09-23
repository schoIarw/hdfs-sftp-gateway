package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.traffic.ConcurrencyGate;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.common.util.threads.CloseableExecutorService;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/** Creates {@link HfgSftpSubsystem} instances so that SFTP never touches the gateway host disk. */
final class HfgSftpSubsystemFactory extends SftpSubsystemFactory implements AutoCloseable {
  private final ConcurrencyGate channels;
  private final int maxChannelsPerSession;
  private final ConcurrentHashMap<ServerSession, ConcurrencyGate> sessionChannels =
      new ConcurrentHashMap<>();
  private final CloseableExecutorService executor;

  HfgSftpSubsystemFactory(
      SftpFileSystemAccessor accessor,
      int maxChannels,
      int maxChannelsPerSession,
      int workerThreads) {
    setFileSystemAccessor(accessor);
    channels = new ConcurrencyGate(maxChannels);
    this.maxChannelsPerSession = maxChannelsPerSession;
    executor = ThreadUtils.newFixedThreadPool("hfg-sftp", workerThreads);
    setExecutorServiceProvider(() -> ThreadUtils.noClose(executor));
  }

  @Override
  public Command createSubsystem(ChannelSession channel) throws IOException {
    ConcurrencyGate.Lease global;
    try {
      global = channels.acquire();
    } catch (RuntimeException limit) {
      throw new IOException("SFTP node channel capacity reached", limit);
    }
    ConcurrencyGate.Lease perSession;
    try {
      perSession =
          sessionChannels
              .computeIfAbsent(
                  channel.getServerSession(), ignored -> new ConcurrencyGate(maxChannelsPerSession))
              .acquire();
    } catch (RuntimeException limit) {
      global.close();
      throw new IOException("SFTP session channel capacity reached", limit);
    }
    try {
      return new HfgSftpSubsystem(
          channel,
          this,
          () -> {
            perSession.close();
            global.close();
          });
    } catch (RuntimeException | Error failure) {
      perSession.close();
      global.close();
      throw failure;
    }
  }

  void sessionClosed(ServerSession session) {
    sessionChannels.remove(session);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
