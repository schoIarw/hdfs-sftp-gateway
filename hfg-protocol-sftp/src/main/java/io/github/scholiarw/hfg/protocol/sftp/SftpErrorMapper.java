package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.contract.HfgErrorCode;
import org.apache.sshd.sftp.common.SftpConstants;

public final class SftpErrorMapper {
  private SftpErrorMapper() {}

  public static int status(HfgErrorCode code) {
    return switch (code) {
      case PATH_NOT_FOUND -> SftpConstants.SSH_FX_NO_SUCH_FILE;
      case PERMISSION_DENIED, AUTH_INVALID, ACCOUNT_DISABLED ->
          SftpConstants.SSH_FX_PERMISSION_DENIED;
      case ALREADY_EXISTS -> SftpConstants.SSH_FX_FILE_ALREADY_EXISTS;
      case UNSUPPORTED_OFFSET -> SftpConstants.SSH_FX_OP_UNSUPPORTED;
      case HDFS_UNAVAILABLE -> SftpConstants.SSH_FX_CONNECTION_LOST;
      default -> SftpConstants.SSH_FX_FAILURE;
    };
  }
}
