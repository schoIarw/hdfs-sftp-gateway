package io.github.scholiarw.hfg.manager.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SshKeyControllerTest {
  @Test
  void normalizesAndFingerprintsOpenSshKey() {
    String key = SshKeyController.normalize("  ssh-ed25519   YWJj   operator@example  ");
    assertEquals("ssh-ed25519 YWJj operator@example", key);
    assertEquals(
        "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", SshKeyController.fingerprint(key));
  }

  @Test
  void rejectsUnknownKeyFormat() {
    assertThrows(IllegalArgumentException.class, () -> SshKeyController.normalize("unknown YWJj"));
  }
}
