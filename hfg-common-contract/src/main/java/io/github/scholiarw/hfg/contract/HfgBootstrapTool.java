package io.github.scholiarw.hfg.contract;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates all Manager bootstrap keys and certificates without invoking OpenSSL. */
public final class HfgBootstrapTool {
  private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final Set<PosixFilePermission> PUBLIC_PERMISSIONS =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

  private HfgBootstrapTool() {}

  public static void main(String[] args) throws Exception {
    Arguments arguments = Arguments.parse(args);
    Generated generated =
        generate(arguments.output(), arguments.serverName(), arguments.serverIp());
    System.out.println("HFG bootstrap completed without OpenSSL.");
    System.out.println("Manager environment: " + generated.managerEnvironment());
    System.out.println("Gateway public environment: " + generated.gatewayEnvironment());
    System.out.println("PKI directory: " + generated.pkiDirectory());
    System.out.println("CA SHA-256 fingerprint: " + generated.caFingerprint());
  }

  static Generated generate(Path output, String serverName, String serverIp) throws Exception {
    if (serverName == null || serverName.isBlank())
      throw new IllegalArgumentException("--server-name is required");
    if (serverName.length() > 253 || !serverName.matches("[A-Za-z0-9._:-]+"))
      throw new IllegalArgumentException("--server-name must be a DNS name or IP literal");
    if (serverIp != null && !serverIp.isBlank()) {
      if (!isIpAddress(serverIp))
        throw new IllegalArgumentException("--server-ip must be an IPv4 or IPv6 literal");
      InetAddress.getByName(serverIp);
    }

    Path root = output.toAbsolutePath().normalize();
    Path pki = root.resolve("pki");
    Path managerEnvironment = root.resolve("hfg-manager-bootstrap.env");
    Path gatewayEnvironment = root.resolve("hfg-gateway-bootstrap.env");
    Path caKey = pki.resolve("ca.key");
    Path caCertificate = pki.resolve("ca.crt");
    Path managerKey = pki.resolve("manager.key");
    Path managerCertificate = pki.resolve("manager.crt");
    List<Path> outputs =
        List.of(
            managerEnvironment,
            gatewayEnvironment,
            caKey,
            caCertificate,
            managerKey,
            managerCertificate);
    for (Path path : outputs)
      if (Files.exists(path)) throw new FileAlreadyExistsException(path.toString());

    Files.createDirectories(pki);
    List<Path> created = new ArrayList<>();
    try {
      KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
      rsa.initialize(3072);
      KeyPair caPair = rsa.generateKeyPair();
      KeyPair managerPair = rsa.generateKeyPair();
      KeyPairGenerator ed25519 = KeyPairGenerator.getInstance("Ed25519");
      KeyPair snapshotPair = ed25519.generateKeyPair();

      X509Certificate ca = caCertificate(caPair);
      X509Certificate manager = managerCertificate(ca, caPair, managerPair, serverName, serverIp);
      write(caKey, pem("PRIVATE KEY", caPair.getPrivate().getEncoded()), true, created);
      write(caCertificate, pem("CERTIFICATE", ca.getEncoded()), false, created);
      write(managerKey, pem("PRIVATE KEY", managerPair.getPrivate().getEncoded()), true, created);
      write(managerCertificate, pem("CERTIFICATE", manager.getEncoded()), false, created);

      String privateKey =
          Base64.getEncoder().encodeToString(snapshotPair.getPrivate().getEncoded());
      String publicKey = Base64.getEncoder().encodeToString(snapshotPair.getPublic().getEncoded());
      String managerVariables =
          "HFG_SNAPSHOT_PRIVATE_KEY_BASE64="
              + privateKey
              + System.lineSeparator()
              + "HFG_SNAPSHOT_PUBLIC_KEY_BASE64="
              + publicKey
              + System.lineSeparator()
              + "HFG_RPC_SERVER_CERT="
              + managerCertificate
              + System.lineSeparator()
              + "HFG_RPC_SERVER_KEY="
              + managerKey
              + System.lineSeparator()
              + "HFG_RPC_CA="
              + caCertificate
              + System.lineSeparator()
              + "HFG_RPC_CA_KEY="
              + caKey
              + System.lineSeparator();
      write(
          managerEnvironment, managerVariables.getBytes(StandardCharsets.US_ASCII), true, created);
      write(
          gatewayEnvironment,
          ("HFG_SNAPSHOT_PUBLIC_KEY_BASE64=" + publicKey + System.lineSeparator())
              .getBytes(StandardCharsets.US_ASCII),
          false,
          created);
      return new Generated(
          managerEnvironment,
          gatewayEnvironment,
          pki,
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ca.getEncoded())));
    } catch (Exception failure) {
      for (int i = created.size() - 1; i >= 0; i--) Files.deleteIfExists(created.get(i));
      throw failure;
    }
  }

  private static X509Certificate caCertificate(KeyPair pair) throws Exception {
    Instant from = Instant.now().minus(5, ChronoUnit.MINUTES);
    Instant until = from.plus(3650, ChronoUnit.DAYS);
    X500Principal subject = new X500Principal("CN=HFG RPC Root CA,OU=HFG RPC,O=HFG,C=CN");
    var builder =
        new JcaX509v3CertificateBuilder(
            subject, serial(), Date.from(from), Date.from(until), subject, pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    X509Certificate certificate =
        new JcaX509CertificateConverter()
            .getCertificate(
                builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
    certificate.verify(pair.getPublic());
    return certificate;
  }

  private static X509Certificate managerCertificate(
      X509Certificate ca, KeyPair caPair, KeyPair managerPair, String serverName, String serverIp)
      throws Exception {
    Instant from = Instant.now().minus(5, ChronoUnit.MINUTES);
    Instant until = from.plus(825, ChronoUnit.DAYS);
    var builder =
        new JcaX509v3CertificateBuilder(
            ca,
            serial(),
            Date.from(from),
            Date.from(until),
            new X500Principal("CN=" + serverName + ",OU=HFG Manager,O=HFG,C=CN"),
            managerPair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    builder.addExtension(
        Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
    List<GeneralName> names = new ArrayList<>();
    names.add(
        new GeneralName(
            isIpAddress(serverName) ? GeneralName.iPAddress : GeneralName.dNSName, serverName));
    if (serverIp != null && !serverIp.isBlank() && !serverIp.equals(serverName))
      names.add(new GeneralName(GeneralName.iPAddress, serverIp));
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(names.toArray(GeneralName[]::new)));
    X509Certificate certificate =
        new JcaX509CertificateConverter()
            .getCertificate(
                builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(caPair.getPrivate())));
    certificate.verify(ca.getPublicKey());
    return certificate;
  }

  private static boolean isIpAddress(String value) {
    return value.matches("[0-9.]+") || value.contains(":");
  }

  private static BigInteger serial() {
    return new BigInteger(159, new SecureRandom()).setBit(158);
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

  private static void write(Path path, byte[] content, boolean secret, List<Path> created)
      throws Exception {
    Files.write(path, content, java.nio.file.StandardOpenOption.CREATE_NEW);
    created.add(path);
    try {
      Files.setPosixFilePermissions(path, secret ? PRIVATE_PERMISSIONS : PUBLIC_PERMISSIONS);
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX development platforms retain their platform default permissions.
    }
  }

  private record Arguments(Path output, String serverName, String serverIp) {
    static Arguments parse(String[] args) {
      Path output = null;
      String serverName = null;
      String serverIp = null;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--output" -> output = Path.of(value(args, ++i, "--output"));
          case "--server-name" -> serverName = value(args, ++i, "--server-name");
          case "--server-ip" -> serverIp = value(args, ++i, "--server-ip");
          case "--help", "-h" -> {
            usage();
            System.exit(0);
          }
          default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
        }
      }
      if (output == null || serverName == null) {
        usage();
        throw new IllegalArgumentException("--output and --server-name are required");
      }
      return new Arguments(output, serverName, serverIp);
    }

    private static String value(String[] args, int index, String option) {
      if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
      return args[index];
    }

    private static void usage() {
      System.err.println(
          "Usage: java -jar hfg-bootstrap.jar --output /etc/hfg "
              + "--server-name hfg-manager.example.com [--server-ip 10.0.10.10]");
    }
  }

  record Generated(
      Path managerEnvironment, Path gatewayEnvironment, Path pkiDirectory, String caFingerprint) {}
}
