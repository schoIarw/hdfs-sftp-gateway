package io.github.scholiarw.hfg.control;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.control.v1.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;

public final class GrpcControlClient implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(GrpcControlClient.class);
  private final Settings settings;
  private final AtomicSnapshotStore store;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "hfg-control-client");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean subscribing = new AtomicBoolean();
  private ManagedChannel channel;

  public GrpcControlClient(Settings settings, AtomicSnapshotStore store) {
    this.settings = settings;
    this.store = store;
  }

  public void start() throws Exception {
    var ssl =
        GrpcSslContexts.forClient()
            .trustManager(settings.caCertificate().toFile())
            .keyManager(settings.clientCertificate().toFile(), settings.clientPrivateKey().toFile())
            .build();
    var builder =
        NettyChannelBuilder.forAddress(settings.host(), settings.port())
            .sslContext(ssl)
            .maxInboundMessageSize(34 * 1024 * 1024)
            .keepAliveTime(30, TimeUnit.SECONDS);
    if (settings.serverName() != null && !settings.serverName().isBlank())
      builder.overrideAuthority(settings.serverName());
    channel = builder.build();
    subscribe();
    scheduler.scheduleWithFixedDelay(
        this::heartbeat, 0, settings.heartbeatInterval().toSeconds(), TimeUnit.SECONDS);
  }

  private void subscribe() {
    if (channel == null || !subscribing.compareAndSet(false, true)) return;
    var request =
        SubscribeSnapshotsRequest.newBuilder()
            .setGatewayId(settings.gatewayId())
            .setServiceGroupId(settings.serviceGroupId())
            .setCurrentVersion(store.version())
            .build();
    HfgControlPlaneGrpc.newStub(channel)
        .subscribeSnapshots(
            request,
            new StreamObserver<>() {
              public void onNext(SnapshotEnvelope e) {
                try {
                  store.install(
                      new SignedSnapshotEnvelope(
                          e.getPayloadJson(), e.getPayloadSha256(), e.getSignatureBase64()));
                } catch (Exception x) {
                  log.error("Rejected control-plane snapshot", x);
                }
              }

              public void onError(Throwable t) {
                subscribing.set(false);
                log.warn("Control-plane stream disconnected: {}", Status.fromThrowable(t));
                scheduler.schedule(GrpcControlClient.this::subscribe, 5, TimeUnit.SECONDS);
              }

              public void onCompleted() {
                onError(
                    Status.UNAVAILABLE
                        .withDescription("Control-plane stream completed")
                        .asRuntimeException());
              }
            });
  }

  private void heartbeat() {
    if (channel == null) return;
    try {
      HfgControlPlaneGrpc.newBlockingStub(channel)
          .withDeadlineAfter(5, TimeUnit.SECONDS)
          .heartbeat(
              GatewayHeartbeat.newBuilder()
                  .setGatewayId(settings.gatewayId())
                  .setServiceGroupId(settings.serviceGroupId())
                  .setHostname(settings.hostname())
                  .setRole(settings.role())
                  .setManagementAddress(settings.managementAddress())
                  .setSoftwareVersion(settings.softwareVersion())
                  .setSnapshotVersion(store.version())
                  .setIpAddress(settings.ipAddress())
                  .setFtpPort(settings.ftpPort())
                  .setSftpPort(settings.sftpPort())
                  .setManagementPort(settings.managementPort())
                  .build());
    } catch (Exception e) {
      log.debug("Control-plane heartbeat failed: {}", e.getMessage());
    }
  }

  public HdfsBundle downloadHdfsBundle() {
    if (channel == null) throw new IllegalStateException("Control-plane channel is not started");
    HdfsBundleResponse response =
        HfgControlPlaneGrpc.newBlockingStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .getHdfsBundle(
                HdfsBundleRequest.newBuilder()
                    .setGatewayId(settings.gatewayId())
                    .setServiceGroupId(settings.serviceGroupId())
                    .build());
    return new HdfsBundle(response.getZip().toByteArray(), response.getSha256());
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    if (channel != null) channel.shutdownNow();
  }

  public record Settings(
      String host,
      int port,
      String serverName,
      java.nio.file.Path caCertificate,
      java.nio.file.Path clientCertificate,
      java.nio.file.Path clientPrivateKey,
      String gatewayId,
      String serviceGroupId,
      String hostname,
      String role,
      String managementAddress,
      String ipAddress,
      int ftpPort,
      int sftpPort,
      int managementPort,
      String softwareVersion,
      Duration heartbeatInterval) {}

  public record HdfsBundle(byte[] zip, String sha256) {}
}
