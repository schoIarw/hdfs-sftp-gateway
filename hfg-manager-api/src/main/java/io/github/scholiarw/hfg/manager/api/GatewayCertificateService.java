package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class GatewayCertificateService {
  private static final Logger log = LoggerFactory.getLogger(GatewayCertificateService.class);
  // Legacy OpenSSL encrypted PEM ("Proc-Type: 4,ENCRYPTED") needs the BouncyCastle provider for its
  // PBKDF-OpenSSL key derivation. The provider instance is used explicitly instead of being
  // registered globally so that the rest of the manager keeps the platform providers.
  private static final BouncyCastleProvider BC_PROVIDER = new BouncyCastleProvider();
  private final JdbcClient db;
  private final Path caCertificate;
  private final Path caPrivateKey;
  private final String caPrivateKeyPassword;
  private final int validityDays;

  GatewayCertificateService(
      JdbcClient db,
      @Value("${hfg.rpc.ca-certificate:/etc/hfg/pki/ca.crt}") Path caCertificate,
      @Value("${hfg.rpc.ca-private-key:/etc/hfg/pki/ca.key}") Path caPrivateKey,
      @Value("${hfg.rpc.ca-private-key-password:}") String caPrivateKeyPassword,
      @Value("${hfg.rpc.gateway-certificate-validity-days:365}") int validityDays) {
    this.db = db;
    this.caCertificate = caCertificate;
    this.caPrivateKey = caPrivateKey;
    this.caPrivateKeyPassword = caPrivateKeyPassword == null ? "" : caPrivateKeyPassword;
    this.validityDays = validityDays;
  }

  Generated generate(String gatewayId, String serviceGroupId, String actor) throws Exception {
    if (!gatewayId.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}"))
      throw new HfgException(HfgErrorCode.CONFIG_INVALID, "Gateway 标识不合法：" + gatewayId);
    db.sql("select count(*) from service_group where id=:id")
        .param("id", serviceGroupId)
        .query(Long.class)
        .optional()
        .filter(count -> count == 1)
        .orElseThrow(() -> new NoSuchElementException("Service group not found"));
    X509Certificate ca = loadIssuerCertificate();
    PrivateKey issuerKey;
    try {
      issuerKey = privateKey(Files.readString(caPrivateKey));
    } catch (HfgException e) {
      throw e;
    } catch (Exception e) {
      throw caFailure("CA 私钥无法读取或解析：" + caPrivateKey, e);
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
    KeyPair pair = generator.generateKeyPair();
    Instant from = Instant.now().minusSeconds(300);
    Instant until =
        Collections.min(
            List.of(
                from.plus(validityDays, java.time.temporal.ChronoUnit.DAYS),
                ca.getNotAfter().toInstant()));
    if (!until.isAfter(from))
      throw new HfgException(HfgErrorCode.CONFIG_INVALID, "CA 证书即将过期，无法签发 Gateway 证书");
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
    X509Certificate certificate;
    try {
      certificate =
          new JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new JcaContentSignerBuilder(
                              "RSA".equalsIgnoreCase(issuerKey.getAlgorithm())
                                  ? "SHA256withRSA"
                                  : "SHA256withECDSA")
                          .build(issuerKey)));
      certificate.verify(ca.getPublicKey());
    } catch (Exception e) {
      throw caFailure("使用配置的 CA 签发证书失败：请确认 CA 私钥与证书匹配", e);
    }
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
        .param("until", java.sql.Timestamp.from(until))
        .param("actor", actor)
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
    return new Generated(
        zip(gatewayId, certificate, pair.getPrivate(), pem("CERTIFICATE", ca.getEncoded())),
        fingerprint,
        until);
  }

  private X509Certificate loadIssuerCertificate() throws Exception {
    if (!Files.isReadable(caCertificate))
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID,
          "CA 证书不存在或不可读：" + caCertificate + "（当前运行用户为 " + System.getProperty("user.name") + "）");
    X509Certificate ca;
    try {
      ca = certificate(Files.readAllBytes(caCertificate));
    } catch (Exception e) {
      throw caFailure("CA 证书无法解析：" + caCertificate, e);
    }
    if (ca.getBasicConstraints() < 0)
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID,
          "配置的签发证书不是 CA（缺少 basicConstraints=CA:TRUE）：" + caCertificate);
    if (keyUsageMissingKeyCertSign(ca))
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID, "配置的 CA 证书未授予证书签发用途 keyCertSign：" + caCertificate);
    try {
      ca.checkValidity();
    } catch (CertificateException e) {
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID, "CA 证书已过期或尚未生效：" + caCertificate, null, e);
    }
    return ca;
  }

  private static boolean keyUsageMissingKeyCertSign(X509Certificate certificate) {
    boolean[] usage = certificate.getKeyUsage();
    // An absent keyUsage extension is treated as unrestricted.
    return usage != null && (usage.length <= 5 || !usage[5]);
  }

  /**
   * Reads the configured CA private key.
   *
   * <p>OpenSSL writes three different PEM shapes depending on the command and version: PKCS#8
   * ({@code BEGIN PRIVATE KEY}), PKCS#8 encrypted ({@code BEGIN ENCRYPTED PRIVATE KEY}) and the
   * legacy PKCS#1/SEC1 forms ({@code BEGIN RSA PRIVATE KEY} / {@code BEGIN EC PRIVATE KEY}, with or
   * without {@code Proc-Type: 4,ENCRYPTED}). BouncyCastle parses and decrypts all of them so that
   * an operator-supplied CA key does not have to be converted by hand.
   */
  private PrivateKey privateKey(String pem) throws Exception {
    String password = caPrivateKeyPassword;
    try (PEMParser parser = new PEMParser(new StringReader(pem))) {
      Object parsed = parser.readObject();
      if (parsed == null)
        throw new HfgException(HfgErrorCode.CONFIG_INVALID, "CA 私钥文件为空或不是 PEM 格式：" + caPrivateKey);
      Object key;
      if (parsed instanceof PEMEncryptedKeyPair encrypted) {
        if (password.isEmpty()) throw encryptedKeyWithoutPassword();
        key =
            encrypted.decryptKeyPair(
                new JcePEMDecryptorProviderBuilder()
                    .setProvider(BC_PROVIDER)
                    .build(password.toCharArray()));
      } else if (parsed instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
        if (password.isEmpty()) throw encryptedKeyWithoutPassword();
        key =
            encrypted.decryptPrivateKeyInfo(
                new JceOpenSSLPKCS8DecryptorProviderBuilder()
                    .setProvider(BC_PROVIDER)
                    .build(password.toCharArray()));
      } else {
        key = parsed;
      }
      PrivateKeyInfo info;
      if (key instanceof PEMKeyPair pair) info = pair.getPrivateKeyInfo();
      else if (key instanceof PrivateKeyInfo privateKeyInfo) info = privateKeyInfo;
      else if (key instanceof java.security.KeyPair pair) return pair.getPrivate();
      else
        throw new HfgException(
            HfgErrorCode.CONFIG_INVALID,
            "不支持的 CA 私钥格式（支持 PKCS#8、PKCS#1 RSA、SEC1 EC）：" + key.getClass().getSimpleName());
      return new JcaPEMKeyConverter().setProvider(BC_PROVIDER).getPrivateKey(info);
    } catch (HfgException e) {
      throw e;
    } catch (Exception e) {
      String hint =
          password.isEmpty()
              ? "；如私钥已加密，请通过 HFG_RPC_CA_KEY_PASSWORD 提供口令"
              : "；请确认 HFG_RPC_CA_KEY_PASSWORD 口令正确";
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID, "CA 私钥解析失败：" + caPrivateKey + hint, null, e);
    }
  }

  private static HfgException encryptedKeyWithoutPassword() {
    return new HfgException(
        HfgErrorCode.CONFIG_INVALID,
        "CA 私钥已加密，但未配置 HFG_RPC_CA_KEY_PASSWORD 口令；"
            + "请配置口令，或改用未加密的 PKCS#8 私钥（openssl pkcs8 -topk8 -nocrypt）");
  }

  private static HfgException caFailure(String message, Exception cause) {
    log.error("{}: {}", message, cause.getMessage(), cause);
    return new HfgException(
        HfgErrorCode.CONFIG_INVALID, message + "：" + cause.getMessage(), null, cause);
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

  record Generated(byte[] zip, String fingerprint, Instant notAfter) {}
}
