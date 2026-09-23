package io.github.scholiarw.hfg.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

final class GatewayConfigurationValidator {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,127}");

  private GatewayConfigurationValidator() {}

  static void validate(GatewayProperties properties) throws IOException {
    requireId("HFG_GATEWAY_ID", properties.gatewayId());
    requireId("HFG_SERVICE_GROUP_ID", properties.serviceGroupId());
    requireText("HFG_RPC_HOST", properties.rpc().host());
    requireText("HFG_NODE_IP", properties.rpc().advertisedAddress());
    requirePort("HFG_RPC_PORT", properties.rpc().port());
    requirePort("HFG_MANAGEMENT_PORT", properties.rpc().managementPort());
    requirePort("HFG_FTP_PORT", properties.ftp().port());
    requirePort("HFG_SFTP_PORT", properties.sftp().port());
    requirePositive("HFG_HEARTBEAT_INTERVAL", properties.rpc().heartbeatInterval());
    requirePositive("HFG_HDFS_REFRESH_INTERVAL", properties.hdfs().refreshInterval());
    requirePositive("HFG_EVENT_REPORT_INTERVAL", properties.snapshot().eventReportInterval());
    requirePositive("HFG_MAX_ACTIVE_TRANSFERS", properties.concurrency().maxActiveTransfers());
    requirePositive("HFG_MAX_UPLOADS", properties.concurrency().maxUploads());
    requirePositive("HFG_MAX_DOWNLOADS", properties.concurrency().maxDownloads());
    requirePositive("HFG_TRANSFER_ACQUIRE_TIMEOUT", properties.concurrency().acquireTimeout());
    requirePositive("HFG_FTP_MAX_LOGINS", properties.ftp().maxLogins());
    requirePositive("HFG_FTP_WORKER_THREADS", properties.ftp().workerThreads());
    requirePositive("HFG_SFTP_MAX_SESSIONS", properties.sftp().maxSessions());
    requirePositive("HFG_SFTP_MAX_CHANNELS", properties.sftp().maxChannels());
    requirePositive("HFG_SFTP_MAX_CHANNELS_PER_SESSION", properties.sftp().maxChannelsPerSession());
    requirePositive("HFG_SFTP_WORKER_THREADS", properties.sftp().workerThreads());
    requireReadable("HFG_RPC_CA", properties.rpc().caCertificate());
    requireReadable("HFG_RPC_CLIENT_CERT", properties.rpc().clientCertificate());
    requireReadable("HFG_RPC_CLIENT_KEY", properties.rpc().clientPrivateKey());
    ensureWritableParent("HFG_SNAPSHOT_PATH", properties.snapshot().path());
    ensureWritableParent("HFG_EVENT_WAL_PATH", properties.snapshot().eventWalPath());
    ensureWritableParent("HFG_HDFS_RUNTIME_PATH", properties.hdfs().runtimePath());
    if (properties.sftp().enabled())
      requireReadable("HFG_SFTP_HOST_KEY", properties.sftp().hostKeyPath());
    requireWritableIfPresent("HFG_SNAPSHOT_PATH", properties.snapshot().path());
    requireWritableIfPresent("HFG_EVENT_WAL_PATH", properties.snapshot().eventWalPath());
    requireWritableIfPresent("HFG_HDFS_RUNTIME_PATH", properties.hdfs().runtimePath());
    try {
      Base64.getDecoder().decode(properties.snapshot().verificationPublicKeyBase64());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "HFG_SNAPSHOT_PUBLIC_KEY_BASE64 is missing or is not valid Base64", exception);
    }
  }

  private static void requireId(String name, String value) {
    if (value == null || !ID.matcher(value).matches())
      throw new IllegalStateException(
          name + " must be 2-128 characters using letters, digits, dot, underscore or hyphen");
  }

  private static void requireText(String name, String value) {
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
  }

  private static void requirePort(String name, int value) {
    if (value < 1 || value > 65535)
      throw new IllegalStateException(name + " must be between 1 and 65535");
  }

  private static void requirePositive(String name, Duration value) {
    if (value == null || value.isZero() || value.isNegative())
      throw new IllegalStateException(name + " must be a positive ISO-8601 duration");
  }

  private static void requirePositive(String name, int value) {
    if (value < 1) throw new IllegalStateException(name + " must be a positive integer");
  }

  private static void requireReadable(String name, Path path) {
    if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
      throw new IllegalStateException(
          name + " file is missing or not readable by the Gateway process: " + path);
  }

  private static void ensureWritableParent(String name, Path path) throws IOException {
    if (path == null) throw new IllegalStateException(name + " path is required");
    Path parent = path.toAbsolutePath().normalize().getParent();
    if (parent == null) throw new IllegalStateException(name + " has no parent directory: " + path);
    try {
      Files.createDirectories(parent);
    } catch (IOException exception) {
      throw new IOException(
          name + " parent directory cannot be created by the Gateway process: " + parent,
          exception);
    }
    if (!Files.isWritable(parent))
      throw new IllegalStateException(
          name + " parent directory is not writable by the Gateway process: " + parent);
  }

  private static void requireWritableIfPresent(String name, Path path) {
    if (Files.exists(path) && !Files.isWritable(path))
      throw new IllegalStateException(
          name + " exists but is not writable by the Gateway process: " + path);
  }
}
