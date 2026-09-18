package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.control.GrpcControlClient;
import io.github.scholiarw.hfg.storage.hdfs.HadoopConfigurationLoader;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.authentication.util.KerberosUtil;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class HdfsBundleSynchronizer {
  private static final Logger log = LoggerFactory.getLogger(HdfsBundleSynchronizer.class);
  private static final long MAX_ZIP_BYTES = 32L * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
  private final GatewayProperties properties;
  private final ReloadableHdfsStorageClientFactory storage;
  private final GrpcControlClient control;
  private final GatewayRuntimeStatus runtimeStatus;
  private String installedHash;

  HdfsBundleSynchronizer(
      GatewayProperties properties,
      ReloadableHdfsStorageClientFactory storage,
      GrpcControlClient control,
      GatewayRuntimeStatus runtimeStatus) {
    this.properties = properties;
    this.storage = storage;
    this.control = control;
    this.runtimeStatus = runtimeStatus;
  }

  @Scheduled(fixedDelayString = "${hfg.hdfs.refresh-interval:PT1M}")
  void synchronize() {
    try {
      GrpcControlClient.HdfsBundle bundle = control.downloadHdfsBundle();
      byte[] zip = bundle.zip();
      if (zip == null || zip.length == 0)
        throw new IllegalStateException("HDFS configuration bundle is empty");
      if (zip.length > MAX_ZIP_BYTES)
        throw new IllegalArgumentException("HDFS configuration ZIP is too large");
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
      if (!hash.equalsIgnoreCase(bundle.sha256()))
        throw new IllegalArgumentException("HDFS configuration digest mismatch");
      if (hash.equals(installedHash) && storage.ready()) {
        // Nothing to install, but the configuration is present and valid: clear any failure left
        // over from an earlier Manager outage instead of staying degraded forever.
        runtimeStatus.healthy("hdfs");
        return;
      }
      install(zip, hash);
      runtimeStatus.healthy("hdfs");
    } catch (Exception e) {
      runtimeStatus.failed("hdfs", e);
      log.warn("Cannot synchronize HDFS configuration: {}", e.getMessage());
    }
  }

  private void install(byte[] zip, String hash) throws Exception {
    Path root = properties.hdfs().runtimePath().toAbsolutePath().normalize();
    Files.createDirectories(root.getParent());
    Path staging = Files.createTempDirectory(root.getParent(), "hdfs-runtime-");
    List<Path> files = new ArrayList<>();
    try {
      long total = 0;
      try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
        for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
          Path output = staging.resolve(entry.getName()).normalize();
          if (!output.startsWith(staging)) throw new IllegalArgumentException("Unsafe ZIP entry");
          if (entry.isDirectory()) {
            Files.createDirectories(output);
          } else if (output.getFileName().toString().endsWith(".xml")
              || output.getFileName().toString().endsWith(".keytab")) {
            Files.createDirectories(output.getParent());
            try (OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
              byte[] buffer = new byte[8192];
              for (int read; (read = input.read(buffer)) >= 0; ) {
                total += read;
                if (total > MAX_EXPANDED_BYTES)
                  throw new IllegalArgumentException("Expanded HDFS bundle is too large");
                out.write(buffer, 0, read);
              }
            }
            files.add(output);
          }
        }
      }
      Path keytab =
          files.stream()
              .filter(p -> p.getFileName().toString().endsWith(".keytab"))
              .findFirst()
              .orElseThrow();
      List<Path> xml =
          files.stream().filter(p -> p.getFileName().toString().endsWith(".xml")).toList();
      String[] principals =
          KerberosUtil.getPrincipalNames(keytab.toString(), java.util.regex.Pattern.compile(".*"));
      if (principals.length == 0 || xml.isEmpty())
        throw new IllegalArgumentException("Incomplete HDFS configuration bundle");
      Path keytabRelative = staging.relativize(keytab);
      List<Path> xmlRelative = xml.stream().map(staging::relativize).toList();
      if (Files.exists(root))
        try (var walk = Files.walk(root)) {
          for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
      Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
      Path installedKeytab = root.resolve(keytabRelative);
      List<String> installedXml =
          xmlRelative.stream().map(root::resolve).map(Path::toString).toList();
      Configuration configuration = HadoopConfigurationLoader.load(installedXml);
      String defaultFs = configuration.getTrimmed("fs.defaultFS");
      if (defaultFs == null || defaultFs.isBlank())
        throw new IllegalArgumentException("fs.defaultFS is missing");
      storage.install(defaultFs, installedXml, principals[0], installedKeytab.toString());
      installedHash = hash;
      log.info("Installed HDFS configuration bundle {}", hash);
    } finally {
      if (Files.exists(staging)) {
        try (var walk = Files.walk(staging)) {
          for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
      }
    }
  }
}
