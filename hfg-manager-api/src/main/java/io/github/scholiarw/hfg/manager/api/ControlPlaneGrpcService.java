package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.control.v1.*;
import io.grpc.Status;
import io.grpc.stub.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class ControlPlaneGrpcService extends HfgControlPlaneGrpc.HfgControlPlaneImplBase {
  private final SnapshotPublisher publisher;
  private final JdbcClient db;
  private final HdfsBundleService hdfsBundles;
  private final ConcurrentMap<String, CopyOnWriteArrayList<StreamObserver<SnapshotEnvelope>>>
      subscribers = new ConcurrentHashMap<>();

  ControlPlaneGrpcService(
      SnapshotPublisher publisher, JdbcClient db, HdfsBundleService hdfsBundles) {
    this.publisher = publisher;
    this.db = db;
    this.hdfsBundles = hdfsBundles;
  }

  @Override
  public void subscribeSnapshots(
      SubscribeSnapshotsRequest request, StreamObserver<SnapshotEnvelope> observer) {
    if (!authorized(request.getGatewayId(), request.getServiceGroupId(), observer)) return;
    var list =
        subscribers.computeIfAbsent(request.getServiceGroupId(), g -> new CopyOnWriteArrayList<>());
    list.add(observer);
    if (observer instanceof ServerCallStreamObserver<SnapshotEnvelope> s)
      s.setOnCancelHandler(() -> list.remove(observer));
    try {
      SignedSnapshotEnvelope latest = publisher.latest(request.getServiceGroupId());
      long version = publisher.version(latest);
      if (version > request.getCurrentVersion()) observer.onNext(proto(latest));
    } catch (NoSuchElementException ignored) {
    }
  }

  @Override
  public void heartbeat(GatewayHeartbeat r, StreamObserver<HeartbeatAck> observer) {
    if (!authorized(r.getGatewayId(), r.getServiceGroupId(), observer)) return;
    Instant n = Instant.now();
    db.sql(
            "insert into gateway_node(id,service_group_id,hostname,role,management_address,ip_address,ftp_port,sftp_port,management_port,software_version,snapshot_version,last_heartbeat_at,status,created_at,updated_at) values(:id,:g,:h,:role,:addr,:ip,:ftp,:sftp,:management,:version,:snapshot,:n,'UP',:n,:n) on conflict(id) do update set role=excluded.role,management_address=excluded.management_address,ip_address=excluded.ip_address,ftp_port=excluded.ftp_port,sftp_port=excluded.sftp_port,management_port=excluded.management_port,software_version=excluded.software_version,snapshot_version=excluded.snapshot_version,last_heartbeat_at=excluded.last_heartbeat_at,status='UP',updated_at=excluded.updated_at")
        .param("id", r.getGatewayId())
        .param("g", r.getServiceGroupId())
        .param("h", r.getHostname())
        .param("role", r.getRole())
        .param("addr", r.getManagementAddress())
        .param("ip", r.getIpAddress())
        .param("ftp", r.getFtpPort())
        .param("sftp", r.getSftpPort())
        .param("management", r.getManagementPort())
        .param("version", r.getSoftwareVersion())
        .param("snapshot", r.getSnapshotVersion())
        .param("n", n)
        .update();
    long latest =
        db.sql(
                "select coalesce(max(version),0) from config_snapshot where service_group_id=:g and status='PUBLISHED'")
            .param("g", r.getServiceGroupId())
            .query(Long.class)
            .single();
    // A snapshot may have been published by another Manager instance. The shared PostgreSQL
    // version check turns the gateway heartbeat into a cross-instance notification path.
    if (latest > r.getSnapshotVersion()) {
      try {
        broadcast(r.getServiceGroupId(), publisher.latest(r.getServiceGroupId()));
      } catch (NoSuchElementException ignored) {
      }
    }
    observer.onNext(
        HeartbeatAck.newBuilder()
            .setServerEpochMillis(n.toEpochMilli())
            .setLatestSnapshotVersion(latest)
            .build());
    observer.onCompleted();
  }

  @Override
  public void getHdfsBundle(
      HdfsBundleRequest request, StreamObserver<HdfsBundleResponse> observer) {
    if (!authorized(request.getGatewayId(), request.getServiceGroupId(), observer)) return;
    try {
      var bundle = hdfsBundles.bundleForGroup(request.getServiceGroupId());
      observer.onNext(
          HdfsBundleResponse.newBuilder()
              .setZip(
                  com.google.protobuf.ByteString.copyFrom(
                      java.nio.file.Files.readAllBytes(bundle.path())))
              .setSha256(bundle.sha256())
              .build());
      observer.onCompleted();
    } catch (NoSuchElementException e) {
      observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
    } catch (IOException e) {
      observer.onError(
          Status.INTERNAL.withDescription("Cannot read HDFS bundle").asRuntimeException());
    }
  }

  void broadcast(String group, SignedSnapshotEnvelope envelope) {
    for (var observer : subscribers.getOrDefault(group, new CopyOnWriteArrayList<>()))
      try {
        observer.onNext(proto(envelope));
      } catch (Exception e) {
        subscribers.get(group).remove(observer);
      }
  }

  private boolean authorized(String gatewayId, String serviceGroupId, StreamObserver<?> observer) {
    String certificateGatewayId = GatewayIdentityInterceptor.GATEWAY_ID.get();
    String fingerprint = GatewayIdentityInterceptor.CERTIFICATE_FINGERPRINT.get();
    boolean certificateMatches = gatewayId.equals(certificateGatewayId);
    boolean activeCertificate =
        certificateMatches
            && db.sql(
                        "select count(*) from gateway_certificate where gateway_id=:gateway and service_group_id=:group and fingerprint_sha256=:fingerprint and status='ACTIVE' and not_before<=:now and not_after>:now")
                    .param("gateway", gatewayId)
                    .param("group", serviceGroupId)
                    .param("fingerprint", fingerprint)
                    .param("now", Instant.now())
                    .query(Long.class)
                    .single()
                > 0;
    if (activeCertificate) return true;
    observer.onError(
        Status.PERMISSION_DENIED
            .withDescription("Gateway certificate is not authorized for this identity and group")
            .asRuntimeException());
    return false;
  }

  private static SnapshotEnvelope proto(SignedSnapshotEnvelope e) {
    return SnapshotEnvelope.newBuilder()
        .setPayloadJson(e.payloadJson())
        .setPayloadSha256(e.payloadSha256())
        .setSignatureBase64(e.signatureBase64())
        .build();
  }
}
