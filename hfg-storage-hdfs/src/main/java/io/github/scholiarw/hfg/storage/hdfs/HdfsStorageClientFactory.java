package io.github.scholiarw.hfg.storage.hdfs;

import io.github.scholiarw.hfg.storage.StorageClient;
import io.github.scholiarw.hfg.storage.StorageClientFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;

public final class HdfsStorageClientFactory implements StorageClientFactory {
  private final Configuration configuration;
  private final UserGroupInformation loginUser;
  private final boolean proxyUsers;

  /**
   * One HDFS client per effective user. Creating a {@code FileSystem} per operation costs a
   * DFSClient with its RPC connection, threads and (with Kerberos) a keytab login - measurable for
   * metadata heavy workloads. The pool is closed when the HDFS configuration is replaced.
   */
  private final Map<UserGroupInformation, HdfsStorageClient> clients = new ConcurrentHashMap<>();

  public HdfsStorageClientFactory(Settings settings) throws IOException {
    configuration = HadoopConfigurationLoader.load(settings.configurationResources());
    if (settings.defaultFs() != null && !settings.defaultFs().isBlank())
      configuration.set("fs.defaultFS", settings.defaultFs());
    proxyUsers = settings.proxyUsers();
    boolean kerberos =
        settings.kerberosPrincipal() != null && !settings.kerberosPrincipal().isBlank();
    String keytab = settings.keytabPath();
    if (kerberos && (keytab == null || keytab.isBlank() || !Files.isReadable(Path.of(keytab))))
      throw new IOException("HDFS keytab 不存在或不可读：" + keytab);
    if (kerberos) enableKerberos(configuration);
    try {
      UserGroupInformation.setConfiguration(configuration);
    } catch (RuntimeException e) {
      throw new IOException(
          "Hadoop 安全配置初始化失败，请确认运行主机存在可用的 /etc/krb5.conf（realm/KDC）：" + e.getMessage(), e);
    }
    if (!kerberos) {
      loginUser = UserGroupInformation.getCurrentUser();
      return;
    }
    // UserGroupInformation.loginUserFromKeytabAndReturnUGI() silently returns the current SIMPLE
    // user
    // when the Hadoop configuration does not enable Kerberos, which surfaces much later as
    // "SIMPLE authentication is not enabled. Available:[TOKEN, KERBEROS]". Fail fast instead.
    if (!UserGroupInformation.isSecurityEnabled())
      throw new IOException(
          "Hadoop 配置未启用 Kerberos 认证（hadoop.security.authentication=kerberos 缺失或被 final 覆盖）："
              + settings.configurationResources());
    loginUser =
        UserGroupInformation.loginUserFromKeytabAndReturnUGI(settings.kerberosPrincipal(), keytab);
    if (loginUser.getAuthenticationMethod() != AuthenticationMethod.KERBEROS)
      throw new IOException(
          "Kerberos 登录未生效，当前认证方式为 "
              + loginUser.getAuthenticationMethod()
              + "（principal="
              + settings.kerberosPrincipal()
              + "）");
  }

  private static void enableKerberos(Configuration configuration) {
    try {
      configuration.set("hadoop.security.authentication", "kerberos");
    } catch (RuntimeException ignored) {
      // The property is marked final in the uploaded XML; keep the operator value so that the
      // security check above reports the mismatch explicitly.
    }
  }

  @Override
  public StorageClient forEffectiveUser(String effectiveUser) throws IOException {
    UserGroupInformation actor =
        proxyUsers && effectiveUser != null && !effectiveUser.isBlank()
            ? UserGroupInformation.createProxyUser(effectiveUser, loginUser)
            : loginUser;
    HdfsStorageClient existing = clients.get(actor);
    if (existing != null) return existing;
    HdfsStorageClient created = newClient(actor);
    HdfsStorageClient raced = clients.putIfAbsent(actor, created);
    if (raced != null) {
      created.close();
      return raced;
    }
    return created;
  }

  private HdfsStorageClient newClient(UserGroupInformation actor) throws IOException {
    try {
      FileSystem fs =
          actor.doAs(
              (PrivilegedExceptionAction<FileSystem>) () -> FileSystem.newInstance(configuration));
    // The factory owns this FileSystem. Individual operations reuse the client without closing it;
    // retiring the factory must close the underlying DFSClient and its sockets/threads.
    return new HdfsStorageClient(fs, true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while creating HDFS client", e);
    }
  }

  /** Closes every pooled client; called when the HDFS configuration is replaced. */
  public void close() {
    for (HdfsStorageClient client : new ArrayList<>(clients.values())) {
      try {
        client.close();
      } catch (IOException ignored) {
        // best effort - the configuration is being replaced anyway
      }
    }
    clients.clear();
  }

  public record Settings(
      String defaultFs,
      List<String> configurationResources,
      String kerberosPrincipal,
      String keytabPath,
      boolean proxyUsers) {
    public Settings {
      configurationResources =
          configurationResources == null ? List.of() : List.copyOf(configurationResources);
    }
  }
}
