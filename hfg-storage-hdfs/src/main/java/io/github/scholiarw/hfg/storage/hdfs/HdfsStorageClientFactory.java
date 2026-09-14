package io.github.scholiarw.hfg.storage.hdfs;

import io.github.scholiarw.hfg.storage.StorageClient;
import io.github.scholiarw.hfg.storage.StorageClientFactory;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;

public final class HdfsStorageClientFactory implements StorageClientFactory {
  private final Configuration configuration;
  private final UserGroupInformation loginUser;
  private final boolean proxyUsers;

  public HdfsStorageClientFactory(Settings settings) throws IOException {
    configuration = new Configuration(false);
    for (String resource : settings.configurationResources()) configuration.addResource(resource);
    if (settings.defaultFs() != null && !settings.defaultFs().isBlank())
      configuration.set("fs.defaultFS", settings.defaultFs());
    UserGroupInformation.setConfiguration(configuration);
    proxyUsers = settings.proxyUsers();
    if (settings.kerberosPrincipal() != null && !settings.kerberosPrincipal().isBlank()) {
      loginUser =
          UserGroupInformation.loginUserFromKeytabAndReturnUGI(
              settings.kerberosPrincipal(), settings.keytabPath());
    } else {
      loginUser = UserGroupInformation.getCurrentUser();
    }
  }

  @Override
  public StorageClient forEffectiveUser(String effectiveUser) throws IOException {
    UserGroupInformation actor =
        proxyUsers && effectiveUser != null && !effectiveUser.isBlank()
            ? UserGroupInformation.createProxyUser(effectiveUser, loginUser)
            : loginUser;
    try {
      FileSystem fs =
          actor.doAs(
              (PrivilegedExceptionAction<FileSystem>) () -> FileSystem.newInstance(configuration));
      return new HdfsStorageClient(fs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while creating HDFS client", e);
    }
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
