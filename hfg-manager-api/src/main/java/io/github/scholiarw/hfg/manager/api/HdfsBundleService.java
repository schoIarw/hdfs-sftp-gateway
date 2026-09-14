package io.github.scholiarw.hfg.manager.api;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.authentication.util.KerberosUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
class HdfsBundleService {
  private static final long MAX_ARCHIVE_BYTES = 32L * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
  private final JdbcClient db;
  private final Path root;

  HdfsBundleService(
      JdbcClient db, @Value("${hfg.hdfs-bundles.path:/var/lib/hfg/hdfs-bundles}") Path root) {
    this.db = db;
    this.root = root.toAbsolutePath().normalize();
  }

  @Transactional
  Map<String, Object> install(String id, String name, MultipartFile archive) throws IOException {
    if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}"))
      throw new IllegalArgumentException("Invalid HDFS cluster id");
    if (archive.isEmpty() || archive.getSize() > MAX_ARCHIVE_BYTES)
      throw new IllegalArgumentException("HDFS ZIP must be between 1 byte and 32 MiB");
    Path directory = root.resolve(id).normalize();
    if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid cluster path");
    Path staging = Files.createTempDirectory(root(), id + "-");
    try {
      Path bundle = staging.resolve("bundle.zip");
      try (InputStream in = archive.getInputStream()) {
        Files.copy(in, bundle, StandardCopyOption.REPLACE_EXISTING);
      }
      List<Path> files = extract(bundle, staging.resolve("content"));
      List<Path> xml =
          files.stream().filter(p -> p.getFileName().toString().endsWith(".xml")).toList();
      Path keytab =
          files.stream()
              .filter(p -> p.getFileName().toString().endsWith(".keytab"))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException("ZIP does not contain a .keytab file"));
      if (xml.isEmpty())
        throw new IllegalArgumentException("ZIP does not contain Hadoop XML files");
      String principal = principal(keytab);
      Configuration configuration = new Configuration(false);
      xml.forEach(p -> configuration.addResource(new org.apache.hadoop.fs.Path(p.toUri())));
      String defaultFs = configuration.getTrimmed("fs.defaultFS");
      if (defaultFs == null || defaultFs.isBlank())
        throw new IllegalArgumentException("Hadoop XML does not define fs.defaultFS");
      String sha = HexFormat.of().formatHex(digest(bundle));
      Path keytabRelative = staging.relativize(keytab);
      List<Path> xmlRelative = xml.stream().map(staging::relativize).toList();
      replaceDirectory(directory, staging);
      Path savedBundle = directory.resolve("bundle.zip");
      Instant now = Instant.now();
      db.sql(
              "insert into hdfs_cluster(id,name,default_fs,nameservice,kerberos_enabled,principal,keytab_secret_ref,config_resource_refs,bundle_path,bundle_sha256,status,created_at,updated_at) values(:id,:name,:fs,:ns,true,:principal,:key,:resources,:bundle,:sha,'ENABLED',:now,:now) on conflict(id) do update set name=excluded.name,default_fs=excluded.default_fs,nameservice=excluded.nameservice,kerberos_enabled=true,principal=excluded.principal,keytab_secret_ref=excluded.keytab_secret_ref,config_resource_refs=excluded.config_resource_refs,bundle_path=excluded.bundle_path,bundle_sha256=excluded.bundle_sha256,status='ENABLED',updated_at=excluded.updated_at")
          .param("id", id)
          .param("name", name)
          .param("fs", defaultFs)
          .param("ns", configuration.getTrimmed("dfs.nameservices"))
          .param("principal", principal)
          .param("key", "file:" + directory.resolve(keytabRelative))
          .param(
              "resources",
              xmlRelative.stream()
                  .map(directory::resolve)
                  .map(Path::toString)
                  .collect(java.util.stream.Collectors.joining(",")))
          .param("bundle", savedBundle.toString())
          .param("sha", sha)
          .param("now", now)
          .update();
      return db.sql("select * from hdfs_cluster where id=:id").param("id", id).query().singleRow();
    } catch (IOException | RuntimeException e) {
      deleteIfPresent(staging);
      throw e;
    }
  }

  Bundle bundleForGroup(String group) {
    Map<String, Object> row =
        db.sql(
                "select h.bundle_path,h.bundle_sha256 from service_group g join hdfs_cluster h on h.id=g.hdfs_cluster_id where g.id=:g and h.status='ENABLED'")
            .param("g", group)
            .query()
            .singleRow();
    Path path = Path.of(String.valueOf(row.get("bundle_path")));
    if (!Files.isRegularFile(path)) throw new NoSuchElementException("HDFS bundle unavailable");
    return new Bundle(path, String.valueOf(row.get("bundle_sha256")));
  }

  private Path root() throws IOException {
    Files.createDirectories(root);
    return root;
  }

  static List<Path> extract(Path zip, Path target) throws IOException {
    Files.createDirectories(target);
    List<Path> files = new ArrayList<>();
    long total = 0;
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
        Path output = target.resolve(entry.getName()).normalize();
        if (!output.startsWith(target)) throw new IllegalArgumentException("Unsafe ZIP entry");
        if (entry.isDirectory()) {
          Files.createDirectories(output);
          continue;
        }
        String filename = output.getFileName().toString();
        if (!(filename.endsWith(".xml") || filename.endsWith(".keytab"))) continue;
        Files.createDirectories(output.getParent());
        try (OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
          total += input.transferTo(new LimitedOutputStream(out, MAX_EXPANDED_BYTES - total));
        }
        if (total > MAX_EXPANDED_BYTES)
          throw new IllegalArgumentException("Expanded ZIP is too large");
        files.add(output);
      }
    }
    return files;
  }

  private static String principal(Path path) {
    try {
      String[] principals =
          KerberosUtil.getPrincipalNames(path.toString(), java.util.regex.Pattern.compile(".*"));
      if (principals.length == 0)
        throw new IllegalArgumentException("Keytab contains no principal");
      return principals[0];
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read keytab", e);
    }
  }

  private static byte[] digest(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest));
      return digest.digest();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void replaceDirectory(Path target, Path staging) throws IOException {
    if (Files.exists(target))
      try (var walk = Files.walk(target)) {
        for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    Files.createDirectories(target.getParent());
    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void deleteIfPresent(Path target) {
    if (!Files.exists(target)) return;
    try (var walk = Files.walk(target)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup; the original validation/storage exception remains authoritative.
    }
  }

  private static final class LimitedOutputStream extends FilterOutputStream {
    private long remaining;

    LimitedOutputStream(OutputStream out, long remaining) {
      super(out);
      this.remaining = remaining;
    }

    public void write(int value) throws IOException {
      require(1);
      out.write(value);
    }

    public void write(byte[] bytes, int offset, int length) throws IOException {
      require(length);
      out.write(bytes, offset, length);
    }

    private void require(long amount) {
      if (amount > remaining) throw new IllegalArgumentException("Expanded ZIP is too large");
      remaining -= amount;
    }
  }

  record Bundle(Path path, String sha256) {}
}
