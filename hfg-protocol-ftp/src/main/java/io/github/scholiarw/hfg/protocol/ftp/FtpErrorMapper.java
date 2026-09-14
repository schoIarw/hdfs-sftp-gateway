package io.github.scholiarw.hfg.protocol.ftp;

import io.github.scholiarw.hfg.contract.HfgErrorCode;

public final class FtpErrorMapper {
  private FtpErrorMapper() {}

  public static int reply(HfgErrorCode code) {
    return switch (code) {
      case AUTH_INVALID, ACCOUNT_DISABLED -> 530;
      case PATH_NOT_FOUND, PERMISSION_DENIED, ALREADY_EXISTS -> 550;
      case QUOTA_EXCEEDED -> 552;
      case RATE_LIMITED, HDFS_UNAVAILABLE, INTERNAL_ERROR -> 451;
      case UNSUPPORTED_OFFSET, INVALID_PATH, CONFIG_INVALID -> 550;
    };
  }
}
