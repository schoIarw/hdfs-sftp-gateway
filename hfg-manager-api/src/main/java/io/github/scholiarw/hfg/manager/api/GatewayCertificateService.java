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
import java.security.spec.X509EncodedKeySpec;
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
  private final DatabaseDialect dialect;
  private final Path caCertificate;
  private final Path caPrivateKey;
  private final String caPrivateKeyPassword;
  private final String snapshotPublicKey;
  private final int validityDays;

  GatewayCertificateService(
      JdbcClient db,
      DatabaseDialect dialect,
      @Value("${hfg.rpc.ca-certificate:/etc/hfg/pki/ca.crt}") Path caCertificate,
      @Value("${hfg.rpc.ca-private-key:/etc/hfg/pki/ca.key}") Path caPrivateKey,
      @Value("${hfg.rpc.ca-private-key-password:}") String caPrivateKeyPassword,
      @Value("${hfg.snapshot.signing-public-key-base64:}") String snapshotPublicKey,
      @Value("${hfg.rpc.gateway-certificate-validity-days:365}") int validityDays) {
    this.db = db;
    this.dialect = dialect;
    this.caCertificate = caCertificate;
    this.caPrivateKey = caPrivateKey;
    this.caPrivateKeyPassword = caPrivateKeyPassword == null ? "" : caPrivateKeyPassword;
    this.snapshotPublicKey = snapshotPublicKey == null ? "" : snapshotPublicKey.trim();
    this.validityDays = validityDays;
  }

  Generated generate(String gatewayId, String serviceGroupId, String actor) throws Exception {
    if (!gatewayId.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}"))
      throw new HfgException(HfgErrorCode.CONFIG_INVALID, "Gateway 标识不合法：" + gatewayId);
    if (snapshotPublicKey.isBlank())
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID,
          "Manager 未配置快照验签公钥；请先运行 hfg-bootstrap.jar 并加载 " + "/etc/hfg/hfg-manager-bootstrap.env");
    try {
      KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(snapshotPublicKey)));
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID,
          "HFG_SNAPSHOT_PUBLIC_KEY_BASE64 不是有效的 Ed25519 X.509 公钥",
          null,
          exception);
    }
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
    String certificatePem =
        new String(pem("CERTIFICATE", certificate.getEncoded()), StandardCharsets.US_ASCII);
    db.sql(
            "insert into"
                + " gateway_certificate(id,gateway_id,service_group_id,serial_number,fingerprint_sha256,not_before,not_after,status,created_by,created_at,certificate_pem)"
                + " values(:id,:gateway,:group,:serial,:fingerprint,:from,:until,'ACTIVE',:actor,:now,:pem)")
        .param("id", UUID.randomUUID())
        .param("gateway", gatewayId)
        .param("group", serviceGroupId)
        .param("serial", serial.toString(16))
        .param("fingerprint", fingerprint)
        .param("from", java.sql.Timestamp.from(from))
        .param("until", java.sql.Timestamp.from(until))
        .param("actor", actor)
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .param("pem", certificatePem)
        .update();
    return new Generated(
        zip(
            gatewayId,
            serviceGroupId,
            snapshotPublicKey,
            certificate,
            pair.getPrivate(),
            pem("CERTIFICATE", ca.getEncoded())),
        fingerprint,
        until);
  }

  /**
   * 证书清单：列出已签发证书，并标注当前是否有 Gateway 节点正在使用。
   *
   * <p>“在用”需要同时满足：证书状态为 ACTIVE、仍在有效期内、绑定节点心跳未超时，并且该节点实际上报的
   * 证书指纹与本条记录一致。换发证书后旧证书会立刻显示为未使用，即使节点还挂在同一标识上。
   */
  List<Map<String, Object>> inventory() {
    List<Map<String, Object>> rows =
        new ArrayList<>(
            db.sql(
                    "select c.id,c.gateway_id,c.service_group_id,c.serial_number,c.fingerprint_sha256,"
                        + "c.not_before,c.not_after,c.status as certificate_status,c.created_by,c.created_at,"
                        + "n.hostname as node_hostname,n.ip_address as node_ip,n.status as node_status,"
                        + "n.last_heartbeat_at as node_last_heartbeat,"
                        + "case when n.id is null then 0 else 1 end as node_present,"
                        + "case when n.certificate_fingerprint=c.fingerprint_sha256 then 1 else 0 end as fingerprint_matches,"
                        + "case when n.last_heartbeat_at is not null and n.last_heartbeat_at>:cutoff then 1 else 0 end as node_online,"
                        + "case when c.not_after<=:now then 1 else 0 end as expired,"
                        + "case when c.certificate_pem is null then 0 else 1 end as stored"
                        + " from gateway_certificate c"
                        + " left join gateway_node n on n.id=c.gateway_id and n.service_group_id=c.service_group_id"
                        + " order by c.gateway_id,c.created_at desc")
                .param("cutoff", java.sql.Timestamp.from(GatewayPresence.cutoff()))
                .param("now", java.sql.Timestamp.from(Instant.now()))
                .query()
                .listOfRows());
    for (Map<String, Object> row : rows) {
      boolean active = "ACTIVE".equals(String.valueOf(row.get("certificate_status")));
      boolean expired = flag(row.get("expired"));
      boolean online = flag(row.get("node_online"));
      boolean matches = flag(row.get("fingerprint_matches"));
      row.put("expired", expired);
      row.put("node_online", online);
      row.put("fingerprint_matches_node", matches);
      row.put("in_use", active && !expired && online && matches);
      row.put("downloadable", flag(row.get("stored")));
    }
    return rows;
  }

  record Download(String gatewayId, String serviceGroupId, String fingerprint, byte[] zip) {}

  /**
   * 下载已签发证书的归档（gateway.crt + ca.crt + 说明）。
   *
   * <p>私钥只在生成时随 ZIP 下发一次，Manager 不保存私钥，因此这里无法重新下载完整凭据；需要完整凭据时 应重新生成证书。
   */
  Download download(UUID certificateId) throws IOException {
    Map<String, Object> row =
        db
            .sql(
                "select gateway_id,service_group_id,fingerprint_sha256,certificate_pem"
                    + " from gateway_certificate where id=:id")
            .param("id", dialect.id(certificateId))
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("证书不存在或已被清理"));
    String gatewayId = String.valueOf(row.get("gateway_id"));
    String serviceGroupId = String.valueOf(row.get("service_group_id"));
    String fingerprint = String.valueOf(row.get("fingerprint_sha256"));
    Object stored = row.get("certificate_pem");
    if (stored == null || String.valueOf(stored).isBlank())
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID, "该证书由旧版本签发，未保存证书正文；请在“Gateway 证书”中重新生成后再下载");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      entry(zip, "gateway.crt", String.valueOf(stored).getBytes(StandardCharsets.US_ASCII));
      entry(zip, "ca.crt", Files.readAllBytes(caCertificate));
      entry(
          zip,
          "README.txt",
          ("Gateway: "
                  + gatewayId
                  + "\nService group: "
                  + serviceGroupId
                  + "\nFingerprint: "
                  + fingerprint
                  + "\n\n本归档只包含已签发的证书正文与 CA 证书，不含私钥：私钥仅在生成证书时下发一次，Manager 不保存。"
                  + "\n如需完整凭据（含私钥）请重新生成证书；安装时把 gateway.crt 覆盖到 /etc/hfg/pki/gateway.crt 并重启 Gateway。\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return new Download(gatewayId, serviceGroupId, fingerprint, bytes.toByteArray());
  }

  private static boolean flag(Object value) {
    if (value instanceof Number number) return number.intValue() != 0;
    return Boolean.TRUE.equals(value);
  }

  /**
   * 删除一张证书记录。
   *
   * <p>正在使用的证书（有效期内、绑定节点在线、且节点实际上报的指纹就是这张）不允许删除，避免把运行中的 Gateway 直接锁死在控制面之外；已换发、已过期或节点长期离线的证书可以清理。
   */
  void delete(UUID certificateId) {
    Map<String, Object> row =
        db
            .sql(
                "select gateway_id,service_group_id,fingerprint_sha256 from gateway_certificate"
                    + " where id=:id")
            .param("id", dialect.id(certificateId))
            .query()
            .listOfRows()
            .stream()
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("证书不存在或已被删除"));
    long inUse =
        db.sql(
                "select count(*) from gateway_certificate c join gateway_node n"
                    + " on n.id=c.gateway_id and n.service_group_id=c.service_group_id"
                    + " where c.id=:id and c.status='ACTIVE' and c.not_after>:now"
                    + " and n.certificate_fingerprint=c.fingerprint_sha256 and n.last_heartbeat_at>:cutoff")
            .param("id", dialect.id(certificateId))
            .param("now", java.sql.Timestamp.from(Instant.now()))
            .param("cutoff", java.sql.Timestamp.from(GatewayPresence.cutoff()))
            .query(Long.class)
            .single();
    if (inUse > 0)
      throw new IllegalStateException(
          "证书 “"
              + row.get("gateway_id")
              + "”（"
              + row.get("service_group_id")
              + "）正在被 Gateway 使用，不能删除；请先停止该节点，或先换发新证书并在节点上生效后再删除。");
    db.sql("delete from gateway_certificate where id=:id")
        .param("id", dialect.id(certificateId))
        .update();
    log.info(
        "Deleted gateway certificate {} for gateway {} in service group {}",
        certificateId,
        row.get("gateway_id"),
        row.get("service_group_id"));
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
      String gatewayId,
      String serviceGroupId,
      String snapshotPublicKey,
      X509Certificate certificate,
      PrivateKey key,
      byte[] ca)
      throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      entry(zip, "gateway.crt", pem("CERTIFICATE", certificate.getEncoded()));
      entry(zip, "gateway.key", pem("PRIVATE KEY", key.getEncoded()));
      entry(zip, "ca.crt", ca);
      String environment = gatewayEnvironment(gatewayId, serviceGroupId, snapshotPublicKey);
      entry(zip, "hfg-gateway-bootstrap.env", environment.getBytes(StandardCharsets.US_ASCII));
      entry(
          zip,
          "README.txt",
          ("Gateway: "
                  + gatewayId
                  + "\nService group: "
                  + serviceGroupId
                  + "\nInstall *.crt/*.key under /etc/hfg/pki and install "
                  + "hfg-gateway-bootstrap.env under /etc/hfg.\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toByteArray();
  }

  static String gatewayEnvironment(
      String gatewayId, String serviceGroupId, String snapshotPublicKey) {
    return "HFG_GATEWAY_ID="
        + gatewayId
        + "\nHFG_SERVICE_GROUP_ID="
        + serviceGroupId
        + "\nHFG_RPC_CA=/etc/hfg/pki/ca.crt"
        + "\nHFG_RPC_CLIENT_CERT=/etc/hfg/pki/gateway.crt"
        + "\nHFG_RPC_CLIENT_KEY=/etc/hfg/pki/gateway.key\n"
        + "HFG_SNAPSHOT_PUBLIC_KEY_BASE64="
        + snapshotPublicKey
        + "\n";
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
