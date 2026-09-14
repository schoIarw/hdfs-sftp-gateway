package io.github.scholiarw.hfg.protocol.ftp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.HfgErrorCode;
import org.junit.jupiter.api.Test;

class FtpErrorMapperTest {
  @Test
  void mapsStableProtocolCodes() {
    assertEquals(530, FtpErrorMapper.reply(HfgErrorCode.AUTH_INVALID));
    assertEquals(552, FtpErrorMapper.reply(HfgErrorCode.QUOTA_EXCEEDED));
    assertEquals(451, FtpErrorMapper.reply(HfgErrorCode.HDFS_UNAVAILABLE));
  }
}
