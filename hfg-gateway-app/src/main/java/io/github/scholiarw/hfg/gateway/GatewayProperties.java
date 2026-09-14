package io.github.scholiarw.hfg.gateway;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hfg")
public record GatewayProperties(
    String gatewayId,
    String serviceGroupId,
    Snapshot snapshot,
    Rpc rpc,
    Hdfs hdfs,
    Ftp ftp,
    Sftp sftp) {
  public record Snapshot(
      Path path,
      String verificationPublicKeyBase64,
      String managerUrl,
      String username,
      String password,
      Duration refreshInterval,
      Path eventWalPath,
      Duration eventReportInterval) {}

  public record Rpc(
      boolean enabled,
      String host,
      int port,
      String serverName,
      Path caCertificate,
      Path clientCertificate,
      Path clientPrivateKey,
      String hostname,
      String role,
      String managementAddress,
      String softwareVersion,
      Duration heartbeatInterval) {}

  public record Hdfs(
      String defaultFs,
      List<String> configurationResources,
      String kerberosPrincipal,
      String keytabPath,
      boolean proxyUsers) {}

  public record Ftp(
      boolean enabled,
      String bindAddress,
      int port,
      String passivePorts,
      String passiveExternalAddress,
      boolean activeModeEnabled,
      int idleTimeoutSeconds) {}

  public record Sftp(boolean enabled, String bindAddress, int port, Path hostKeyPath) {}
}
