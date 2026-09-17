package io.github.scholiarw.hfg.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HfgBootstrapToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void generatesCompleteBootstrapAndRefusesOverwrite() throws Exception {
    HfgBootstrapTool.Generated generated =
        HfgBootstrapTool.generate(temporaryDirectory, "manager.example.com", "10.0.10.10");

    Path caPath = generated.pkiDirectory().resolve("ca.crt");
    Path managerPath = generated.pkiDirectory().resolve("manager.crt");
    X509Certificate ca = certificate(caPath);
    X509Certificate manager = certificate(managerPath);
    assertTrue(ca.getBasicConstraints() >= 0);
    manager.verify(ca.getPublicKey());
    assertTrue(
        manager.getSubjectAlternativeNames().stream()
            .anyMatch(value -> "manager.example.com".equals(value.get(1))));
    assertTrue(
        manager.getSubjectAlternativeNames().stream()
            .anyMatch(value -> "10.0.10.10".equals(value.get(1))));
    verifyPrivateKey(manager, generated.pkiDirectory().resolve("manager.key"));

    String managerEnvironment = Files.readString(generated.managerEnvironment());
    String gatewayEnvironment = Files.readString(generated.gatewayEnvironment());
    assertTrue(managerEnvironment.contains("HFG_SNAPSHOT_PRIVATE_KEY_BASE64="));
    assertTrue(managerEnvironment.contains("HFG_SNAPSHOT_PUBLIC_KEY_BASE64="));
    assertTrue(managerEnvironment.contains("HFG_RPC_CA_KEY="));
    assertEquals(
        environmentValue(managerEnvironment, "HFG_SNAPSHOT_PUBLIC_KEY_BASE64"),
        environmentValue(gatewayEnvironment, "HFG_SNAPSHOT_PUBLIC_KEY_BASE64"));
    verifySnapshotPair(managerEnvironment, gatewayEnvironment);

    assertThrows(
        FileAlreadyExistsException.class,
        () -> HfgBootstrapTool.generate(temporaryDirectory, "manager.example.com", "10.0.10.10"));
  }

  @Test
  void rejectsInvalidServerIdentity() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HfgBootstrapTool.generate(temporaryDirectory, "bad manager name", "10.0.10.10"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HfgBootstrapTool.generate(temporaryDirectory, "manager.example.com", "localhost"));
  }

  private static X509Certificate certificate(Path path) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(path)));
  }

  private static void verifySnapshotPair(String manager, String gateway) throws Exception {
    KeyFactory keys = KeyFactory.getInstance("Ed25519");
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(
        keys.generatePrivate(
            new PKCS8EncodedKeySpec(
                Base64.getDecoder()
                    .decode(environmentValue(manager, "HFG_SNAPSHOT_PRIVATE_KEY_BASE64")))));
    signature.update(new byte[] {1, 2, 3});
    byte[] signed = signature.sign();
    signature.initVerify(
        keys.generatePublic(
            new X509EncodedKeySpec(
                Base64.getDecoder()
                    .decode(environmentValue(gateway, "HFG_SNAPSHOT_PUBLIC_KEY_BASE64")))));
    signature.update(new byte[] {1, 2, 3});
    assertTrue(signature.verify(signed));
  }

  private static void verifyPrivateKey(X509Certificate certificate, Path privateKey)
      throws Exception {
    String pem = Files.readString(privateKey);
    byte[] encoded =
        Base64.getMimeDecoder()
            .decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", ""));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded)));
    signature.update(new byte[] {4, 5, 6});
    byte[] signed = signature.sign();
    signature.initVerify(certificate.getPublicKey());
    signature.update(new byte[] {4, 5, 6});
    assertTrue(signature.verify(signed));
  }

  private static String environmentValue(String content, String name) {
    return content
        .lines()
        .filter(line -> line.startsWith(name + "="))
        .map(line -> line.substring(name.length() + 1))
        .findFirst()
        .orElseThrow();
  }
}
