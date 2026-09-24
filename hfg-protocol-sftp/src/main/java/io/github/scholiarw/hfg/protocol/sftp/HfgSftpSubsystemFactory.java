package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.traffic.ConcurrencyGate;
import java.io.IOException;
import java.util.Set;
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
  private final ConcurrentHashMap<ServerSession, SessionState> sessionChannels =
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
    SessionState state =
        sessionChannels.computeIfAbsent(
            channel.getServerSession(), ignored -> new SessionState(maxChannelsPerSession));
    ConcurrencyGate.Lease perSession;
    try {
      perSession = state.gate.acquire();
    } catch (RuntimeException limit) {
      global.close();
      throw new IOException("SFTP session channel capacity reached", limit);
    }
    // Track the global lease on the owning session so a channel torn down without destroy() (e.g.
    // an abnormal disconnect) cannot leak it: sessionClosed() below releases whatever remains.
    state.globalLeases.add(global);
    try {
      return new HfgSftpSubsystem(
          channel,
          this,
          () -> {
            state.globalLeases.remove(global);
            perSession.close();
            global.close();
          });
    } catch (RuntimeException | Error failure) {
      state.globalLeases.remove(global);
      perSession.close();
      global.close();
      throw failure;
    }
  }

  void sessionClosed(ServerSession session) {
    SessionState state = sessionChannels.remove(session);
    if (state == null) return;
    for (ConcurrencyGate.Lease lease : state.globalLeases) lease.close();
    state.globalLeases.clear();
  }

  /**
   * Per-session accounting. {@code gate} limits concurrent channels per session; {@code
   * globalLeases} holds the node-level channel permits still owned by this session. Closing a lease
   * is idempotent, so the normal channel-close callback and the sessionClosed() sweep can both run
   * without double-releasing.
   */
  private static final class SessionState {
    final ConcurrencyGate gate;
    final Set<ConcurrencyGate.Lease> globalLeases = ConcurrentHashMap.newKeySet();

    SessionState(int maxChannelsPerSession) {
      gate = new ConcurrencyGate(maxChannelsPerSession);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}