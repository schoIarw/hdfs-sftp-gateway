package io.github.scholiarw.hfg.control;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.control.v1.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
  private String ftpPassiveExternalAddress;

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
    GatewayConfigurationResponse configuration;
    try {
      configuration =
          HfgControlPlaneGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .getGatewayConfiguration(
                  GatewayConfigurationRequest.newBuilder()
                      .setGatewayId(settings.gatewayId())
                      .setServiceGroupId(settings.serviceGroupId())
                      .build());
    } catch (StatusRuntimeException exception) {
      throw new IllegalStateException(
          "Cannot authenticate with HFG Manager at "
              + settings.host()
              + ":"
              + settings.port()
              + ". Verify HFG_GATEWAY_ID, HFG_SERVICE_GROUP_ID, certificate assignment, CA, "
              + "and HFG_RPC_SERVER_NAME. Manager response: "
              + exception.getStatus(),
          exception);
    }
    ftpPassiveExternalAddress = configuration.getFtpPassiveExternalAddress();
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
                  .setRole("SERVING")
                  .setManagementAddress(settings.managementAddress())
                  .setSoftwareVersion(settings.softwareVersion())
                  .setSnapshotVersion(store.version())
                  .setIpAddress(settings.ipAddress())
                  .setFtpPort(settings.ftpPort())
                  .setSftpPort(settings.sftpPort())
                  .setManagementPort(settings.managementPort())
                  .setRuntimeStatus(settings.lastError().get().isBlank() ? "UP" : "DEGRADED")
                  .setLastError(settings.lastError().get())
                  .build());
    } catch (Exception e) {
      log.warn(
          "Control-plane heartbeat failed for Gateway {} in service group {}: {}",
          settings.gatewayId(),
          settings.serviceGroupId(),
          Status.fromThrowable(e));
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

  public String ftpPassiveExternalAddress() {
    return ftpPassiveExternalAddress;
  }

  public void reportTransferEvents(List<String> eventJson) {
    TransferEventsRequest.Builder request =
        TransferEventsRequest.newBuilder()
            .setGatewayId(settings.gatewayId())
            .setServiceGroupId(settings.serviceGroupId());
    request.addAllEventJson(eventJson);
    stub().reportTransferEvents(request.build());
  }

  public QuotaReservation reserveQuota(UUID userId, String direction, long files, long bytes) {
    QuotaReservationResponse response =
        stub()
            .reserveQuota(
                QuotaReserveRequest.newBuilder()
                    .setGatewayId(settings.gatewayId())
                    .setServiceGroupId(settings.serviceGroupId())
                    .setUserId(userId.toString())
                    .setDirection(direction)
                    .setFiles(files)
                    .setBytes(bytes)
                    .build());
    return new QuotaReservation(
        UUID.fromString(response.getId()), response.getFiles(), response.getBytes());
  }

  public void renewQuota(UUID reservationId) {
    stub()
        .renewQuota(
            QuotaRenewRequest.newBuilder()
                .setGatewayId(settings.gatewayId())
                .setServiceGroupId(settings.serviceGroupId())
                .setReservationId(reservationId.toString())
                .build());
  }

  public void commitQuota(UUID reservationId, long completedFiles, long completedBytes) {
    stub()
        .commitQuota(
            QuotaCommitRequest.newBuilder()
                .setGatewayId(settings.gatewayId())
                .setServiceGroupId(settings.serviceGroupId())
                .setReservationId(reservationId.toString())
                .setCompletedFiles(completedFiles)
                .setCompletedBytes(completedBytes)
                .build());
  }

  private HfgControlPlaneGrpc.HfgControlPlaneBlockingStub stub() {
    if (channel == null) throw new IllegalStateException("Control-plane channel is not started");
    return HfgControlPlaneGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
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
      String managementAddress,
      String ipAddress,
      int ftpPort,
      int sftpPort,
      int managementPort,
      String softwareVersion,
      Duration heartbeatInterval,
      Supplier<String> lastError) {}

  public record HdfsBundle(byte[] zip, String sha256) {}

  public record QuotaReservation(UUID id, long files, long bytes) {}
}
