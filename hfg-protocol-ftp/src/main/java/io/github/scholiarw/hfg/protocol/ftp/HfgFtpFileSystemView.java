package io.github.scholiarw.hfg.protocol.ftp;

import io.github.scholiarw.hfg.contract.UserSnapshot;
import io.github.scholiarw.hfg.transfer.TransferService;
import org.apache.ftpserver.ftplet.*;

final class HfgFtpFileSystemView implements FileSystemView {
  private final UserSnapshot user;
  private final TransferService transfers;
  private final String gatewayId;
  private String workingDirectory = "/";

  HfgFtpFileSystemView(UserSnapshot user, TransferService transfers, String gatewayId) {
    this.user = user;
    this.transfers = transfers;
    this.gatewayId = gatewayId;
  }

  @Override
  public FtpFile getHomeDirectory() {
    return file("/");
  }

  @Override
  public FtpFile getWorkingDirectory() {
    return file(workingDirectory);
  }

  @Override
  public boolean changeWorkingDirectory(String dir) {
    HfgFtpFile next = file(dir);
    if (!next.doesExist() || !next.isDirectory() || !next.isReadable()) return false;
    workingDirectory = next.getAbsolutePath();
    return true;
  }

  @Override
  public FtpFile getFile(String path) {
    return file(path);
  }

  @Override
  public boolean isRandomAccessible() {
    return true;
  }

  @Override
  public void dispose() {}

  private HfgFtpFile file(String path) {
    return new HfgFtpFile(user, transfers, workingDirectory, path, gatewayId);
  }
}
