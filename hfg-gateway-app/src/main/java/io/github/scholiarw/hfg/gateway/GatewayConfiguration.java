package io.github.scholiarw.hfg.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.control.*;
import io.github.scholiarw.hfg.policy.*;
import io.github.scholiarw.hfg.protocol.ftp.*;
import io.github.scholiarw.hfg.protocol.sftp.*;
import io.github.scholiarw.hfg.storage.StorageClientFactory;
import io.github.scholiarw.hfg.transfer.*;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
class GatewayConfiguration {
  @Bean
  AtomicSnapshotStore snapshotStore(GatewayProperties p, ObjectMapper mapper) throws Exception {
    GatewayConfigurationValidator.validate(p);
    PublicKey key;
    try {
      byte[] encoded = Base64.getDecoder().decode(p.snapshot().verificationPublicKeyBase64());
      key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException(
          "HFG_SNAPSHOT_PUBLIC_KEY_BASE64 is not a valid Ed25519 X.509 public key", exception);
    }
    var store =
        new AtomicSnapshotStore(
            p.snapshot().path(), mapper, new SnapshotVerifier(key, mapper), p.serviceGroupId());
    store.loadIfPresent();
    return store;
  }

  @Bean
  CredentialVerifier credentialVerifier() {
    var bcrypt = new BCryptPasswordEncoder();
    return bcrypt::matches;
  }

  @Bean
  ReloadableHdfsStorageClientFactory storageFactory() {
    return new ReloadableHdfsStorageClientFactory();
  }

  @Bean
  PolicyEngine policyEngine() {
    return new PolicyEngine(new PathResolver());
  }

  @Bean
  TransferLimiter transferLimiter(GatewayProperties p, MeterRegistry metrics) {
    var c = p.concurrency();
    var limiter =
        new NodeTransferLimiter(
            new LocalTransferLimiter(),
            c.maxActiveTransfers(),
            c.maxUploads(),
            c.maxDownloads(),
            c.acquireTimeout());
    Gauge.builder("hfg.transfers.active", limiter, NodeTransferLimiter::activeTotal)
        .description("Active transfers admitted on this gateway")
        .tag("direction", "all")
        .register(metrics);
    Gauge.builder("hfg.transfers.active", limiter, value -> value.active(TransferDirection.UPLOAD))
        .tag("direction", "upload")
        .register(metrics);
    Gauge.builder(
            "hfg.transfers.active", limiter, value -> value.active(TransferDirection.DOWNLOAD))
        .tag("direction", "download")
        .register(metrics);
    Gauge.builder("hfg.transfers.waiting", limiter, NodeTransferLimiter::waiting)
        .description("Transfers waiting for node capacity")
        .register(metrics);
    FunctionCounter.builder("hfg.transfers.rejected", limiter, NodeTransferLimiter::rejected)
        .description("Transfers rejected after the bounded capacity wait")
        .register(metrics);
    return limiter;
  }

  @Bean
  TransferService transferService(
      StorageClientFactory storage,
      PolicyEngine policy,
      TransferLimiter limiter,
      GatewayEventReporter events) {
    // GatewayEventReporter already implements TransferEventSink; declaring an extra
    // TransferEventSink bean made the injection ambiguous and prevented startup.
    return new TransferService(storage, policy, limiter, events);
  }

  @Bean(destroyMethod = "close")
  GrpcControlClient grpcControlClient(
      GatewayProperties p,
      AtomicSnapshotStore store,
      GatewayRuntimeStatus runtimeStatus,
      GatewayReadiness readiness)
      throws Exception {
    var r = p.rpc();
    var client =
        new GrpcControlClient(
            new GrpcControlClient.Settings(
                r.host(),
                r.port(),
                r.serverName(),
                r.caCertificate(),
                r.clientCertificate(),
                r.clientPrivateKey(),
                p.gatewayId(),
                p.serviceGroupId(),
                hostname(),
                "http://" + r.advertisedAddress() + ":" + r.managementPort(),
                r.advertisedAddress(),
                p.ftp().port(),
                p.sftp().port(),
                r.managementPort(),
                softwareVersion(),
                r.heartbeatInterval(),
                () -> runtimeState(runtimeStatus, readiness),
                () -> runtimeError(runtimeStatus, readiness)),
            store);
    client.start();
    return client;
  }

  @Bean
  HfgFtpServer ftpServer(
      GatewayProperties p,
      AtomicSnapshotStore users,
      CredentialVerifier verifier,
      TransferService transfers,
      GrpcControlClient control)
      throws Exception {
    var f = p.ftp();
    var server =
        new HfgFtpServer(
            new HfgFtpServer.Settings(
                f.bindAddress(),
                f.port(),
                f.passivePorts(),
                control.ftpPassiveExternalAddress(),
                f.activeModeEnabled(),
                f.idleTimeoutSeconds(),
                f.maxLogins(),
                f.workerThreads()),
            new HfgFtpUserManager(users, verifier, Clock.systemUTC()),
            new HfgFtpFileSystemFactory(users, transfers, p.gatewayId()));
    if (f.enabled()) server.start();
    return server;
  }

  /**
   * What the Manager should display for this node: OUT_OF_SERVICE while it cannot serve at all (no
   * snapshot or no HDFS configuration), DEGRADED while a subsystem keeps failing, UP otherwise.
   */
  private static String runtimeState(GatewayRuntimeStatus status, GatewayReadiness readiness) {
    if (!org.springframework.boot.actuate.health.Status.UP.equals(readiness.health().getStatus()))
      return "OUT_OF_SERVICE";
    return status.summary().isBlank() ? "UP" : "DEGRADED";
  }

  private static String runtimeError(GatewayRuntimeStatus status, GatewayReadiness readiness) {
    String reason = readinessReason(readiness);
    String summary = status.summary();
    if (reason.isBlank()) return summary;
    return summary.isBlank() ? reason : reason + "；" + summary;
  }

  private static String readinessReason(GatewayReadiness readiness) {
    Object reason = readiness.health().getDetails().get("reason");
    return reason == null ? "" : String.valueOf(reason);
  }

  private static String softwareVersion() {
    String version = HfgGatewayApplication.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }

  private static String hostname() {
    try {
      String hostname = InetAddress.getLocalHost().getHostName();
      if (hostname != null && !hostname.isBlank()) return hostname;
    } catch (Exception ignored) {
      // Fall through to the operating-system supplied environment value.
    }
    String hostname = System.getenv("HOSTNAME");
    if (hostname == null || hostname.isBlank())
      throw new IllegalStateException(
          "Cannot determine the local hostname; configure the operating-system hostname or "
              + "HOSTNAME environment variable");
    return hostname;
  }

  @Bean
  HfgSftpServer sftpServer(
      GatewayProperties p,
      AtomicSnapshotStore users,
      CredentialVerifier verifier,
      TransferService transfers)
      throws Exception {
    var s = p.sftp();
    var auth = new HfgSftpAuthenticator(users, verifier, Clock.systemUTC(), s.maxSessions());
    var server =
        new HfgSftpServer(
            new HfgSftpServer.Settings(
                s.bindAddress(),
                s.port(),
                s.hostKeyPath(),
                s.hostKeyAlgorithm(),
                s.idleTimeoutSeconds(),
                s.maxChannels(),
                s.maxChannelsPerSession(),
                s.workerThreads()),
            auth,
            new HfgSftpFileSystemAccessor(users, transfers, p.gatewayId()));
    if (s.enabled()) server.start();
    return server;
  }
}
