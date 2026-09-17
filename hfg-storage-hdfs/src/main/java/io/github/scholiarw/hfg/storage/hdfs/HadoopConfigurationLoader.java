package io.github.scholiarw.hfg.storage.hdfs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.hadoop.conf.Configuration;

/**
 * Loads Hadoop XML configuration resources.
 *
 * <p>{@code Configuration#addResource(String)} treats its argument as a <b>classpath</b> resource
 * (see {@code Configuration#getStreamReader}); an absolute file path such as {@code
 * /var/lib/hfg/hdfs-runtime/core-site.xml} is therefore ignored. Passing a {@link
 * org.apache.hadoop.fs.Path} or URL is required for files. Because Hadoop quiet mode defaults to
 * true, the missing resources produced no diagnostic at all, which left {@code
 * hadoop.security.authentication} unset and silently degraded Kerberos logins to SIMPLE.
 */
public final class HadoopConfigurationLoader {
  private HadoopConfigurationLoader() {}

  public static Configuration load(List<String> resources) {
    Configuration configuration = new Configuration(false);
    for (String resource : resources == null ? List.<String>of() : resources) {
      String value = resource == null ? "" : resource.trim();
      if (value.isEmpty()) continue;
      if (value.contains("://")) {
        configuration.addResource(new org.apache.hadoop.fs.Path(URI.create(value)));
        continue;
      }
      Path file = Path.of(value);
      if (Files.isRegularFile(file)) {
        configuration.addResource(new org.apache.hadoop.fs.Path(file.toUri()));
        continue;
      }
      configuration.addResource(value);
    }
    return configuration;
  }
}
