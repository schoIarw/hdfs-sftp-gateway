package io.github.scholiarw.hfg.manager.api;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class GatewayCertificateService {
  private final JdbcClient db;
  private final Path caCertificate;
  private final Path caPrivateKey;
  private final int validityDays;

  GatewayCertificateService(
      JdbcClient db,
      @Value("${hfg.rpc.ca-certificate:/etc/hfg/pki/ca.crt}") Path caCertificate,
      @Value("${hfg.rpc.ca-private-key:/etc/hfg/pki/ca.key}") Path caPrivateKey,
      @Value("${hfg.rpc.gateway-certificate-validity-days:365}") int validityDays) {
    this.db = db;
    this.caCertificate = caCertificate;
    this.caPrivateKey = caPrivateKey;
    this.validityDays = validityDays;
  }

  Generated generate(String gatewayId, String serviceGroupId, String actor) throws Exception {
    if (!gatewayId.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}"))
      throw new IllegalArgumentException("Invalid gateway id");
    db.sql("select count(*) from service_group where id=:id")
        .param("id", serviceGroupId)
        .query(Long.class)
        .optional()
        .filter(count -> count == 1)
        .orElseThrow(() -> new NoSuchElementException("Service group not found"));
    X509Certificate ca = certificate(Files.readAllBytes(caCertificate));
    ca.checkValidity();
    if (ca.getBasicConstraints() < 0)
      throw new CertificateException("Configured issuer certificate is not a CA");
    PrivateKey issuerKey = privateKey(Files.readString(caPrivateKey));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    Instant from = Instant.now().minusSeconds(300);
    Instant until =
        Collections.min(
            List.of(
                from.plus(validityDays, java.time.temporal.ChronoUnit.DAYS),
                ca.getNotAfter().toInstant()));
    if (!until.isAfter(from)) throw new CertificateException("CA certificate expires too soon");
    BigInteger serial = new BigInteger(160, new SecureRandom()).abs();
    var builder =
        new JcaX509v3CertificateBuilder(
            ca,
            serial,
            Date.from(from),
            Date.from(until),
            new X500Principal("CN=" + gatewayId + ",OU=HFG Gateway"),
            pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
    builder.addExtension(
        Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(new GeneralName(GeneralName.dNSName, gatewayId)));
    X509Certificate certificate =
        new JcaX509CertificateConverter()
            .getCertificate(
                builder.build(
                    new JcaContentSignerBuilder(
                            "RSA".equalsIgnoreCase(issuerKey.getAlgorithm())
                                ? "SHA256withRSA"
                                : "SHA256withECDSA")
                        .build(issuerKey)));
    certificate.verify(ca.getPublicKey());
    String fingerprint =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    db.sql(
            "insert into gateway_certificate(id,gateway_id,service_group_id,serial_number,fingerprint_sha256,not_before,not_after,status,created_by,created_at) values(:id,:gateway,:group,:serial,:fingerprint,:from,:until,'ACTIVE',:actor,:now)")
        .param("id", UUID.randomUUID())
        .param("gateway", gatewayId)
        .param("group", serviceGroupId)
        .param("serial", serial.toString(16))
        .param("fingerprint", fingerprint)
        .param("from", java.sql.Timestamp.from(from))
        .param("until", until)
        .param("actor", actor)
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
    return new Generated(
        zip(gatewayId, certificate, pair.getPrivate(), pem("CERTIFICATE", ca.getEncoded())),
        fingerprint,
        until);
  }

  private static byte[] zip(
      String gatewayId, X509Certificate certificate, PrivateKey key, byte[] ca) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      entry(zip, "gateway.crt", pem("CERTIFICATE", certificate.getEncoded()));
      entry(zip, "gateway.key", pem("PRIVATE KEY", key.getEncoded()));
      entry(zip, "ca.crt", ca);
      entry(
          zip,
          "README.txt",
          ("Gateway: " + gatewayId + "\nInstall with mode 0600 and configure HFG_RPC_* paths.\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toByteArray();
  }

  private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  private static byte[] pem(String type, byte[] der) {
    return ("-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END "
            + type
            + "-----\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static X509Certificate certificate(byte[] pem) throws CertificateException {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem));
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    String value =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(value));
    for (String algorithm : List.of("EC", "RSA"))
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (GeneralSecurityException ignored) {
      }
    throw new InvalidKeyException("Unsupported CA private key");
  }

  record Generated(byte[] zip, String fingerprint, Instant notAfter) {}
}
