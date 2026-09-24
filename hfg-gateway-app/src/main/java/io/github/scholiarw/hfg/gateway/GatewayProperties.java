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
    Concurrency concurrency,
    Ftp ftp,
    Sftp sftp) {
  public record Snapshot(
      Path path,
      String verificationPublicKeyBase64,
      Path eventWalPath,
      Duration eventReportInterval) {}

  public record Rpc(
      String host,
      int port,
      String serverName,
      Path caCertificate,
      Path clientCertificate,
      Path clientPrivateKey,
      String advertisedAddress,
      int managementPort,
      Duration heartbeatInterval) {}

  public record Hdfs(Path runtimePath, Duration refreshInterval) {}

  public record Concurrency(
      int maxActiveTransfers,
      int maxUploads,
      int maxDownloads,
      int maxTransfersPerUser,
      Duration acquireTimeout) {}

  public record Ftp(
      boolean enabled,
      String bindAddress,
      int port,
      String passivePorts,
      boolean activeModeEnabled,
      int idleTimeoutSeconds,
      int maxLogins,
      int workerThreads) {}

  public record Sftp(
      boolean enabled,
      String bindAddress,
      int port,
      Path hostKeyPath,
      String hostKeyAlgorithm,
      int idleTimeoutSeconds,
      int maxSessions,
      int maxChannels,
      int maxChannelsPerSession,
      int workerThreads) {}
}
