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
  private final DatabaseDialect dialect;

  HdfsBundleService(
      JdbcClient db,
      DatabaseDialect dialect,
      @Value("${hfg.hdfs-bundles.path:/var/lib/hfg/hdfs-bundles}") Path root) {
    this.db = db;
    this.dialect = dialect;
    this.root = root.toAbsolutePath().normalize();
  }

  /**
   * Creates a connection or replaces its stored configuration. Uploading a ZIP is the only way a
   * Manager learns about a Hadoop cluster, so this path doubles as "import" and "re-upload".
   */
  @Transactional
  Map<String, Object> install(String id, String name, MultipartFile archive) {
    requireValidId(id);
    if (name == null || name.isBlank()) throw new IllegalArgumentException("HDFS 连接名称不能为空");
    if (archive == null || archive.isEmpty() || archive.getSize() > MAX_ARCHIVE_BYTES)
      throw new IllegalArgumentException("HDFS 配置 ZIP 必须在 1 字节到 32 MiB 之间");
    requireUnusedName(id, name);
    Path directory = directory(id);
    Path staging = createStaging(id);
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
              dialect.choose(
                  "insert into hdfs_cluster(id,name,default_fs,nameservice,kerberos_enabled,principal,keytab_secret_ref,config_resource_refs,bundle_path,bundle_sha256,status,created_at,updated_at) values(:id,:name,:fs,:ns,true,:principal,:key,:resources,:bundle,:sha,'ENABLED',:now,:now) on conflict(id) do update set name=excluded.name,default_fs=excluded.default_fs,nameservice=excluded.nameservice,kerberos_enabled=true,principal=excluded.principal,keytab_secret_ref=excluded.keytab_secret_ref,config_resource_refs=excluded.config_resource_refs,bundle_path=excluded.bundle_path,bundle_sha256=excluded.bundle_sha256,status='ENABLED',updated_at=excluded.updated_at",
                  "insert into hdfs_cluster(id,name,default_fs,nameservice,kerberos_enabled,principal,keytab_secret_ref,config_resource_refs,bundle_path,bundle_sha256,status,created_at,updated_at) values(:id,:name,:fs,:ns,true,:principal,:key,:resources,:bundle,:sha,'ENABLED',:now,:now) on duplicate key update name=values(name),default_fs=values(default_fs),nameservice=values(nameservice),kerberos_enabled=true,principal=values(principal),keytab_secret_ref=values(keytab_secret_ref),config_resource_refs=values(config_resource_refs),bundle_path=values(bundle_path),bundle_sha256=values(bundle_sha256),status='ENABLED',updated_at=values(updated_at)"))
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
          .param("now", java.sql.Timestamp.from(now))
          .update();
      return db.sql("select * from hdfs_cluster where id=:id").param("id", id).query().singleRow();
    } catch (IOException e) {
      deleteIfPresent(staging);
      throw storageFailure("保存 HDFS 配置包", directory, e);
    } catch (RuntimeException e) {
      deleteIfPresent(staging);
      throw e;
    }
  }

  /** Re-uploads the XML/keytab archive of an existing connection without changing its identity. */
  @Transactional
  Map<String, Object> reinstallAuthentication(String id, MultipartFile archive) {
    requireValidId(id);
    String name = String.valueOf(requireRow(id).get("name"));
    return install(id, name, archive);
  }

  /**
   * Removes an HDFS connection and its stored bundle. Deletion is refused while service groups or
   * directory mappings still reference the cluster so that no runtime object is left dangling.
   */
  @Transactional
  void delete(String id) {
    requireValidId(id);
    Map<String, Object> row = requireRow(id);
    requireUnreferenced(
        id,
        db.sql("select name from service_group where hdfs_cluster_id=:id order by name")
            .param("id", id)
            .query(String.class)
            .list(),
        db.sql(
                "select d.name from directory_mapping d where d.hdfs_cluster_id=:id order by d.name")
            .param("id", id)
            .query(String.class)
            .list());
    db.sql("delete from hdfs_cluster where id=:id").param("id", id).update();
    removeDirectory(
        directory(id),
        row.get("bundle_path") == null ? null : String.valueOf(row.get("bundle_path")));
  }

  /**
   * Lists connections together with whether their stored authentication bundle is still present.
   */
  List<Map<String, Object>> list() {
    List<Map<String, Object>> rows =
        new ArrayList<>(db.sql("select * from hdfs_cluster order by name").query().listOfRows());
    for (Map<String, Object> row : rows) {
      boolean present = bundlePresent(row.get("bundle_path"));
      row.put("bundle_available", present);
      row.put("bundle_size_bytes", present ? sizeOf(row.get("bundle_path")) : 0L);
    }
    return rows;
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

  static void requireUnreferenced(String id, List<String> groups, List<String> directories) {
    List<String> references = new ArrayList<>();
    if (!groups.isEmpty()) references.add("服务组 " + String.join("、", groups));
    if (!directories.isEmpty()) references.add("目录映射 " + String.join("、", directories));
    if (references.isEmpty()) return;
    throw new IllegalStateException(
        "HDFS 连接 “"
            + id
            + "” 仍有关联，无法删除："
            + String.join("；", references)
            + "。请先删除绑定的服务组，并在“目录管理”中删除目录映射后再删除该连接");
  }

  static void requireValidId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}"))
      throw new IllegalArgumentException("HDFS 连接标识必须以字母或数字开头，只能包含字母、数字、点、下划线和短横线，长度 2-64 个字符");
  }

  private void requireUnusedName(String id, String name) {
    Integer conflicts =
        db.sql("select count(*) from hdfs_cluster where name=:name and id<>:id")
            .param("name", name)
            .param("id", id)
            .query(Integer.class)
            .single();
    if (conflicts != null && conflicts > 0)
      throw new IllegalStateException("HDFS 连接名称 “" + name + "” 已被其它连接占用");
  }

  private Map<String, Object> requireRow(String id) {
    List<Map<String, Object>> rows =
        db.sql("select * from hdfs_cluster where id=:id").param("id", id).query().listOfRows();
    if (rows.isEmpty()) throw new NoSuchElementException("HDFS 连接 “" + id + "” 不存在");
    return rows.get(0);
  }

  private Path directory(String id) {
    Path directory = root.resolve(id).normalize();
    if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid cluster path");
    return directory;
  }

  private Path root() throws IOException {
    Files.createDirectories(root);
    return root;
  }

  private Path createStaging(String id) {
    try {
      return Files.createTempDirectory(root(), id + "-");
    } catch (IOException e) {
      throw storageFailure("创建配置包临时目录", root, e);
    }
  }

  private static BundleStorageException storageFailure(
      String action, Path path, IOException cause) {
    return new BundleStorageException(
        action
            + "失败："
            + path
            + "（"
            + reason(cause)
            + "）。请确认 Manager 运行用户对 HFG_HDFS_BUNDLE_PATH 目录有读写权限。",
        cause);
  }

  private static String reason(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  static boolean bundlePresent(Object bundlePath) {
    return bundlePath != null && Files.isRegularFile(Path.of(String.valueOf(bundlePath)));
  }

  private static long sizeOf(Object bundlePath) {
    try {
      return Files.size(Path.of(String.valueOf(bundlePath)));
    } catch (IOException | RuntimeException e) {
      return 0L;
    }
  }

  private static void removeDirectory(Path directory, String bundlePath) {
    try {
      deleteTree(directory);
      if (bundlePath != null && !Path.of(bundlePath).normalize().startsWith(directory))
        deleteTree(Path.of(bundlePath).normalize());
    } catch (IOException e) {
      throw new BundleStorageException(
          "HDFS 连接已从数据库删除，但配置文件 “" + directory + "” 清理失败（" + reason(e) + "），请手工删除该路径。", e);
    }
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
    deleteTree(target);
    Files.createDirectories(target.getParent());
    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void deleteTree(Path target) throws IOException {
    if (target == null || !Files.exists(target)) return;
    try (var walk = Files.walk(target)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  private static void deleteIfPresent(Path target) {
    try {
      deleteTree(target);
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
