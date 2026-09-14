package io.github.scholiarw.hfg.protocol.ftp;

import io.github.scholiarw.hfg.contract.UserSnapshotProvider;
import io.github.scholiarw.hfg.transfer.TransferService;
import org.apache.ftpserver.ftplet.*;

public final class HfgFtpFileSystemFactory implements FileSystemFactory {
  private final UserSnapshotProvider users;
  private final TransferService transfers;
  private final String gatewayId;

  public HfgFtpFileSystemFactory(
      UserSnapshotProvider users, TransferService transfers, String gatewayId) {
    this.users = users;
    this.transfers = transfers;
    this.gatewayId = gatewayId;
  }

  @Override
  public FileSystemView createFileSystemView(User user) throws FtpException {
    var snapshot =
        users
            .findByUsername(user.getName())
            .orElseThrow(() -> new FtpException("User snapshot unavailable"));
    return new HfgFtpFileSystemView(snapshot, transfers, gatewayId);
  }
}
