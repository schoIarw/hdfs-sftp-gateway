package io.github.scholiarw.hfg.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotKeyToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void generatesAUsablePairAndRefusesToOverwriteIt() throws Exception {
    SnapshotKeyTool.GeneratedKeys generated = SnapshotKeyTool.generate(temporaryDirectory);
    byte[] privateBytes = decode(generated.managerFile(), "HFG_SNAPSHOT_PRIVATE_KEY_BASE64");
    byte[] publicBytes = decode(generated.gatewayFile(), "HFG_SNAPSHOT_PUBLIC_KEY_BASE64");
    KeyFactory factory = KeyFactory.getInstance("Ed25519");
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
    signer.update("hfg-centos7".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] signature = signer.sign();
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(factory.generatePublic(new X509EncodedKeySpec(publicBytes)));
    verifier.update("hfg-centos7".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(verifier.verify(signature));
    assertEquals(64, generated.publicKeyFingerprint().length());
    assertThrows(
        FileAlreadyExistsException.class, () -> SnapshotKeyTool.generate(temporaryDirectory));
  }

  private static byte[] decode(Path file, String name) throws Exception {
    String value = Files.readString(file).trim();
    return Base64.getDecoder().decode(value.substring((name + "=").length()));
  }
}
