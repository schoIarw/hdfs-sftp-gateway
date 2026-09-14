package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.control.v1.*;
import io.grpc.stub.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class ControlPlaneGrpcService extends HfgControlPlaneGrpc.HfgControlPlaneImplBase {
  private final SnapshotPublisher publisher;
  private final JdbcClient db;
  private final ConcurrentMap<String, CopyOnWriteArrayList<StreamObserver<SnapshotEnvelope>>>
      subscribers = new ConcurrentHashMap<>();

  ControlPlaneGrpcService(SnapshotPublisher publisher, JdbcClient db) {
    this.publisher = publisher;
    this.db = db;
  }

  @Override
  public void subscribeSnapshots(
      SubscribeSnapshotsRequest request, StreamObserver<SnapshotEnvelope> observer) {
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
    Instant n = Instant.now();
    db.sql(
            "insert into gateway_node(id,service_group_id,hostname,role,management_address,software_version,snapshot_version,last_heartbeat_at,status,created_at,updated_at) values(:id,:g,:h,:role,:addr,:version,:snapshot,:n,'UP',:n,:n) on conflict(id) do update set role=excluded.role,management_address=excluded.management_address,software_version=excluded.software_version,snapshot_version=excluded.snapshot_version,last_heartbeat_at=excluded.last_heartbeat_at,status='UP',updated_at=excluded.updated_at")
        .param("id", r.getGatewayId())
        .param("g", r.getServiceGroupId())
        .param("h", r.getHostname())
        .param("role", r.getRole())
        .param("addr", r.getManagementAddress())
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

  void broadcast(String group, SignedSnapshotEnvelope envelope) {
    for (var observer : subscribers.getOrDefault(group, new CopyOnWriteArrayList<>()))
      try {
        observer.onNext(proto(envelope));
      } catch (Exception e) {
        subscribers.get(group).remove(observer);
      }
  }

  private static SnapshotEnvelope proto(SignedSnapshotEnvelope e) {
    return SnapshotEnvelope.newBuilder()
        .setPayloadJson(e.payloadJson())
        .setPayloadSha256(e.payloadSha256())
        .setSignatureBase64(e.signatureBase64())
        .build();
  }
}
