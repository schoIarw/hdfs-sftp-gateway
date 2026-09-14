package io.github.scholiarw.hfg.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.control.*;
import io.github.scholiarw.hfg.policy.*;
import io.github.scholiarw.hfg.protocol.ftp.*;
import io.github.scholiarw.hfg.protocol.sftp.*;
import io.github.scholiarw.hfg.storage.StorageClientFactory;
import io.github.scholiarw.hfg.storage.hdfs.HdfsStorageClientFactory;
import io.github.scholiarw.hfg.transfer.*;
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
    byte[] encoded = Base64.getDecoder().decode(p.snapshot().verificationPublicKeyBase64());
    PublicKey key =
        KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
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
  StorageClientFactory storageFactory(GatewayProperties p) throws Exception {
    var h = p.hdfs();
    return new HdfsStorageClientFactory(
        new HdfsStorageClientFactory.Settings(
            h.defaultFs(),
            h.configurationResources(),
            h.kerberosPrincipal(),
            h.keytabPath(),
            h.proxyUsers()));
  }

  @Bean
  PolicyEngine policyEngine() {
    return new PolicyEngine(new PathResolver());
  }

  @Bean
  TransferLimiter transferLimiter(HttpQuotaLeaseClient quotas) {
    return new LocalTransferLimiter(quotas);
  }

  @Bean
  TransferEventSink transferEventSink(GatewayMetrics metrics, GatewayEventReporter reporter) {
    return event -> {
      metrics.publish(event);
      reporter.publish(event);
    };
  }

  @Bean
  TransferService transferService(
      StorageClientFactory storage,
      PolicyEngine policy,
      TransferLimiter limiter,
      TransferEventSink events) {
    return new TransferService(storage, policy, limiter, events);
  }

  @Bean(destroyMethod = "close")
  GrpcControlClient grpcControlClient(GatewayProperties p, AtomicSnapshotStore store)
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
                r.hostname(),
                r.role(),
                r.managementAddress(),
                r.softwareVersion(),
                r.heartbeatInterval()),
            store);
    if (r.enabled()) client.start();
    return client;
  }

  @Bean
  HfgFtpServer ftpServer(
      GatewayProperties p,
      AtomicSnapshotStore users,
      CredentialVerifier verifier,
      TransferService transfers)
      throws Exception {
    var f = p.ftp();
    var server =
        new HfgFtpServer(
            new HfgFtpServer.Settings(
                f.bindAddress(),
                f.port(),
                f.passivePorts(),
                f.passiveExternalAddress(),
                f.activeModeEnabled(),
                f.idleTimeoutSeconds()),
            new HfgFtpUserManager(users, verifier, Clock.systemUTC()),
            new HfgFtpFileSystemFactory(users, transfers, p.gatewayId()));
    if (f.enabled()) server.start();
    return server;
  }

  @Bean
  HfgSftpServer sftpServer(
      GatewayProperties p,
      AtomicSnapshotStore users,
      CredentialVerifier verifier,
      TransferService transfers)
      throws Exception {
    var s = p.sftp();
    var auth = new HfgSftpAuthenticator(users, verifier, Clock.systemUTC());
    var server =
        new HfgSftpServer(
            new HfgSftpServer.Settings(s.bindAddress(), s.port(), s.hostKeyPath()),
            auth,
            new HfgSftpFileSystemAccessor(users, transfers, p.gatewayId()));
    if (s.enabled()) server.start();
    return server;
  }
}
