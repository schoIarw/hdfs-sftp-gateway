package io.github.scholiarw.hfg.protocol.sftp;

import java.io.IOException;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

/** Creates {@link HfgSftpSubsystem} instances so that SFTP never touches the gateway host disk. */
final class HfgSftpSubsystemFactory extends SftpSubsystemFactory {
  HfgSftpSubsystemFactory(SftpFileSystemAccessor accessor) {
    setFileSystemAccessor(accessor);
  }

  @Override
  public Command createSubsystem(ChannelSession channel) throws IOException {
    return new HfgSftpSubsystem(channel, this);
  }
}
