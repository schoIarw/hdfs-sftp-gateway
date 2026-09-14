package io.github.scholiarw.hfg.protocol.sftp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HfgSftpAuthenticatorTest {
  @Test
  void ignoresAuthorizedKeyComments() {
    assertEquals(
        "ssh-ed25519 AAAATEST",
        HfgSftpAuthenticator.keyMaterial("ssh-ed25519 AAAATEST operator@example"));
    assertEquals("", HfgSftpAuthenticator.keyMaterial("invalid"));
  }
}
