package io.github.scholiarw.hfg.manager.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.contract.TransferEvent;
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
  private final DatabaseDialect dialect;
  private final ObjectMapper mapper;
  private final BusinessLogService businessLogs;
  private final ConcurrentMap<String, CopyOnWriteArrayList<StreamObserver<SnapshotEnvelope>>>
      subscribers = new ConcurrentHashMap<>();

  ControlPlaneGrpcService(
      SnapshotPublisher publisher,
      JdbcClient db,
      HdfsBundleService hdfsBundles,
      DatabaseDialect dialect,
      ObjectMapper mapper,
      BusinessLogService businessLogs) {
    this.publisher = publisher;
    this.db = db;
    this.hdfsBundles = hdfsBundles;
    this.dialect = dialect;
    this.mapper = mapper;
    this.businessLogs = businessLogs;
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
    long duplicateHostname =
        db.sql(
                "select count(*) from gateway_node where service_group_id=:group and hostname=:hostname and id<>:id")
            .param("group", r.getServiceGroupId())
            .param("hostname", r.getHostname())
            .param("id", r.getGatewayId())
            .query(Long.class)
            .single();
    if (duplicateHostname > 0) {
      observer.onError(
          Status.ALREADY_EXISTS
              .withDescription(
                  "Hostname '"
                      + r.getHostname()
                      + "' is already registered by another Gateway in service group '"
                      + r.getServiceGroupId()
                      + "'")
              .asRuntimeException());
      return;
    }
    // 证书共用保护：同一个 Gateway 标识 + 同一张证书，不允许同时由两台主机上报。
    // 只比较主机名：换网卡/IP、DHCP 续租都属正常，而两台机器共用一个标识必然是不同的主机名。
    // 旧节点已离线（心跳超时）时允许接管，覆盖换机、重建节点的正常场景。
    String fingerprint = GatewayIdentityInterceptor.CERTIFICATE_FINGERPRINT.get();
    if (fingerprint != null && !fingerprint.isBlank()) {
      long sharedCertificate =
          db.sql(
                  "select count(*) from gateway_node where id=:id and service_group_id=:g"
                      + " and certificate_fingerprint=:fp and last_heartbeat_at>:cutoff"
                      + " and hostname<>:hostname")
              .param("id", r.getGatewayId())
              .param("g", r.getServiceGroupId())
              .param("fp", fingerprint)
              .param("cutoff", java.sql.Timestamp.from(GatewayPresence.cutoff()))
              .param("hostname", r.getHostname())
              .query(Long.class)
              .single();
      if (sharedCertificate > 0) {
        observer.onError(
            Status.PERMISSION_DENIED
                .withDescription(
                    "Gateway '"
                        + r.getGatewayId()
                        + "' 正在由另一台主机上报（本次来自 "
                        + r.getHostname()
                        + " / "
                        + r.getIpAddress()
                        + "）。同一张 Gateway 证书不允许被多个节点共用：请为该节点单独签发证书；"
                        + "若确实是替换主机，请先停止旧节点，等待其心跳超时后再启动。")
                .asRuntimeException());
        return;
      }
    }
    Instant n = Instant.now();
    db.sql(
            dialect.choose(
                "insert into gateway_node(id,service_group_id,hostname,role,management_address,ip_address,ftp_port,sftp_port,management_port,software_version,snapshot_version,last_heartbeat_at,status,last_error,created_at,updated_at) values(:id,:g,:h,:role,:addr,:ip,:ftp,:sftp,:management,:version,:snapshot,:n,:status,:error,:n,:n) on conflict(id) do update set role=excluded.role,management_address=excluded.management_address,ip_address=excluded.ip_address,ftp_port=excluded.ftp_port,sftp_port=excluded.sftp_port,management_port=excluded.management_port,software_version=excluded.software_version,snapshot_version=excluded.snapshot_version,last_heartbeat_at=excluded.last_heartbeat_at,status=excluded.status,last_error=excluded.last_error,updated_at=excluded.updated_at",
                "insert into gateway_node(id,service_group_id,hostname,role,management_address,ip_address,ftp_port,sftp_port,management_port,software_version,snapshot_version,last_heartbeat_at,status,last_error,created_at,updated_at) values(:id,:g,:h,:role,:addr,:ip,:ftp,:sftp,:management,:version,:snapshot,:n,:status,:error,:n,:n) on duplicate key update role=values(role),management_address=values(management_address),ip_address=values(ip_address),ftp_port=values(ftp_port),sftp_port=values(sftp_port),management_port=values(management_port),software_version=values(software_version),snapshot_version=values(snapshot_version),last_heartbeat_at=values(last_heartbeat_at),status=values(status),last_error=values(last_error),updated_at=values(updated_at)"))
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
        .param("status", r.getRuntimeStatus().isBlank() ? "UP" : r.getRuntimeStatus())
        .param("error", r.getLastError())
        .param("n", java.sql.Timestamp.from(n))
        .update();
    // 记录该节点本次实际上报的证书指纹，用于证书清单的“在用”判断和共用检测。
    if (fingerprint != null && !fingerprint.isBlank())
      db.sql(
              "update gateway_node set certificate_fingerprint=:fp where id=:id and service_group_id=:g")
          .param("fp", fingerprint)
          .param("id", r.getGatewayId())
          .param("g", r.getServiceGroupId())
          .update();
    long latest =
        db.sql(
                "select coalesce(max(version),0) from config_snapshot where service_group_id=:g and status='PUBLISHED'")
            .param("g", r.getServiceGroupId())
            .query(Long.class)
            .single();
    // A snapshot may have been published by another Manager instance. The shared management
    // database
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

  @Override
  public void getGatewayConfiguration(
      GatewayConfigurationRequest request, StreamObserver<GatewayConfigurationResponse> observer) {
    if (!authorized(request.getGatewayId(), request.getServiceGroupId(), observer)) return;
    try {
      String vip =
          db.sql("select vip from service_group where id=:id and status='ENABLED'")
              .param("id", request.getServiceGroupId())
              .query(String.class)
              .optional()
              .orElseThrow(
                  () ->
                      new NoSuchElementException(
                          "Service group '"
                              + request.getServiceGroupId()
                              + "' does not exist or is disabled"));
      observer.onNext(
          GatewayConfigurationResponse.newBuilder().setFtpPassiveExternalAddress(vip).build());
      observer.onCompleted();
    } catch (NoSuchElementException e) {
      observer.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void reportTransferEvents(
      TransferEventsRequest request, StreamObserver<OperationAck> observer) {
    if (!authorized(request.getGatewayId(), request.getServiceGroupId(), observer)) return;
    if (request.getEventJsonCount() > 1000) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Maximum event batch size is 1000")
              .asRuntimeException());
      return;
    }
    try {
      for (String json : request.getEventJsonList()) {
        TransferEvent event = mapper.readValue(json, TransferEvent.class);
        if (!request.getGatewayId().equals(event.gatewayId()))
          throw new IllegalArgumentException(
              "Transfer event gateway ID does not match certificate identity");
        if (userBelongsToAnotherGroup(event.userId(), request.getServiceGroupId()))
          throw new IllegalArgumentException(
              "Transfer event user does not belong to the Gateway service group");
        businessLogs.ingest(event);
      }
      observer.onNext(OperationAck.getDefaultInstance());
      observer.onCompleted();
    } catch (Exception e) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
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
                    .param("now", java.sql.Timestamp.from(Instant.now()))
                    .query(Long.class)
                    .single()
                > 0;
    if (activeCertificate) return true;
    String reason =
        !certificateMatches
            ? "Configured HFG_GATEWAY_ID '"
                + gatewayId
                + "' does not match client certificate CN '"
                + certificateGatewayId
                + "'"
            : "Gateway certificate is not active for service group '"
                + serviceGroupId
                + "'; verify HFG_SERVICE_GROUP_ID and regenerate the certificate for that group";
    observer.onError(Status.PERMISSION_DENIED.withDescription(reason).asRuntimeException());
    return false;
  }

  /**
   * Events of users that no longer exist are accepted: they are history recorded while the user was
   * still valid, and rejecting them would leave the gateway's event queue permanently stuck. Only
   * events attributed to a live user of another service group are rejected.
   */
  private boolean userBelongsToAnotherGroup(UUID userId, String serviceGroupId) {
    Integer count =
        db.sql("select count(*) from ftp_user where id=:id and service_group_id<>:group")
            .param("id", dialect.id(userId))
            .param("group", serviceGroupId)
            .query(Integer.class)
            .single();
    return count != null && count > 0;
  }

  private static SnapshotEnvelope proto(SignedSnapshotEnvelope e) {
    return SnapshotEnvelope.newBuilder()
        .setPayloadJson(e.payloadJson())
        .setPayloadSha256(e.payloadSha256())
        .setSignatureBase64(e.signatureBase64())
        .build();
  }
}
