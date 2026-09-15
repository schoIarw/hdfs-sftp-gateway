package io.github.scholiarw.hfg.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** Generates the Ed25519 keys used to sign manager-to-gateway snapshots. */
public final class SnapshotKeyTool {
  static final String MANAGER_FILE = "hfg-snapshot-manager.env";
  static final String GATEWAY_FILE = "hfg-snapshot-gateway.env";

  private SnapshotKeyTool() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || "--help".equals(args[0]) || "-h".equals(args[0])) {
      System.err.println(
          "Usage: java -cp hfg-keytool.jar "
              + SnapshotKeyTool.class.getName()
              + " <output-directory>");
      System.exit(args.length == 1 ? 0 : 2);
    }
    GeneratedKeys generated = generate(Path.of(args[0]));
    System.out.println("Manager private-key environment: " + generated.managerFile());
    System.out.println("Gateway public-key environment: " + generated.gatewayFile());
    System.out.println("Public-key SHA-256 fingerprint: " + generated.publicKeyFingerprint());
  }

  static GeneratedKeys generate(Path outputDirectory) throws Exception {
    Path directory = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path managerFile = directory.resolve(MANAGER_FILE);
    Path gatewayFile = directory.resolve(GATEWAY_FILE);
    if (Files.exists(managerFile)) {
      throw new FileAlreadyExistsException(managerFile.toString());
    }
    if (Files.exists(gatewayFile)) {
      throw new FileAlreadyExistsException(gatewayFile.toString());
    }

    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    KeyPair pair = generator.generateKeyPair();
    String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    writeNew(managerFile, "HFG_SNAPSHOT_PRIVATE_KEY_BASE64=" + privateKey + System.lineSeparator());
    try {
      writeNew(gatewayFile, "HFG_SNAPSHOT_PUBLIC_KEY_BASE64=" + publicKey + System.lineSeparator());
    } catch (Exception failure) {
      Files.deleteIfExists(managerFile);
      throw failure;
    }
    setPermissions(
        managerFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    setPermissions(
        gatewayFile,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    String fingerprint =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded()));
    return new GeneratedKeys(managerFile, gatewayFile, fingerprint);
  }

  private static void writeNew(Path path, String value) throws IOException {
    Files.writeString(
        path,
        value,
        StandardCharsets.US_ASCII,
        java.nio.file.StandardOpenOption.CREATE_NEW,
        java.nio.file.StandardOpenOption.WRITE);
  }

  private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX development platforms retain their platform default permissions.
    }
  }

  record GeneratedKeys(Path managerFile, Path gatewayFile, String publicKeyFingerprint) {}
}
