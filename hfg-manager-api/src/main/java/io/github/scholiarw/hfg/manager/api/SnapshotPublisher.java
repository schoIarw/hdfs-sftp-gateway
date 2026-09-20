package io.github.scholiarw.hfg.manager.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.AccessMode;
import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.contract.DirectoryGrant;
import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import io.github.scholiarw.hfg.contract.SnapshotPayload;
import io.github.scholiarw.hfg.contract.TrafficPolicy;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SnapshotPublisher {
  private final JdbcClient db;
  private final DatabaseDialect dialect;
  private final ObjectMapper mapper;
  private final String privateKeyBase64;

  SnapshotPublisher(
      JdbcClient db,
      DatabaseDialect dialect,
      ObjectMapper mapper,
      @Value("${hfg.snapshot.signing-private-key-base64:}") String privateKeyBase64) {
    this.db = db;
    this.dialect = dialect;
    this.mapper = mapper;
    this.privateKeyBase64 = privateKeyBase64;
  }

  @Transactional
  SignedSnapshotEnvelope publish(String group, String actor) {
    return publishLocked(group, actor, true)
        .orElseThrow(() -> new IllegalStateException("Forced snapshot publication was skipped"));
  }

  @Transactional
  Optional<SignedSnapshotEnvelope> publishIfChanged(String group, String actor) {
    return publishLocked(group, actor, false);
  }

  private Optional<SignedSnapshotEnvelope> publishLocked(
      String group, String actor, boolean force) {
    if (privateKeyBase64.isBlank()) {
      throw new IllegalStateException("Snapshot signing key is not configured");
    }
    db.sql("select id from service_group where id=:g for update")
        .param("g", group)
        .query(String.class)
        .single();
    try {
      List<UserSnapshot> users = snapshotUsers(group);
      String sourceHash = sourceHash(group, users);
      if (!force) {
        Optional<String> latestSource =
            db.sql(
                    "select source_sha256 from config_snapshot where service_group_id=:g and"
                        + " status='PUBLISHED' order by version desc limit 1")
                .param("g", group)
                .query(String.class)
                .optional();
        if (latestSource.filter(sourceHash::equals).isPresent()) return Optional.empty();
      }
      long version =
          db.sql("select coalesce(max(version),0)+1 from config_snapshot where service_group_id=:g")
              .param("g", group)
              .query(Long.class)
              .single();
      String payload =
          mapper.writeValueAsString(new SnapshotPayload(version, group, Instant.now(), users));
      byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey());
      signer.update(bytes);
      String signature = Base64.getEncoder().encodeToString(signer.sign());
      db.sql(
              "insert into"
                  + " config_snapshot(id,service_group_id,version,payload_json,payload_sha256,source_sha256,signature,status,created_by,created_at)"
                  + " values(:id,:g,:v,:p,:h,:source,:s,'PUBLISHED',:a,:now)")
          .param("id", dialect.id(UUID.randomUUID()))
          .param("g", group)
          .param("v", version)
          .param("p", payload)
          .param("h", hash)
          .param("source", sourceHash)
          .param("s", signature)
          .param("a", actor)
          .param("now", java.sql.Timestamp.from(Instant.now()))
          .update();
      return Optional.of(new SignedSnapshotEnvelope(payload, hash, signature));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot sign snapshot", exception);
    }
  }

  private List<UserSnapshot> snapshotUsers(String group) {
    // Materialize users before issuing child queries. Some JDBC drivers allow only one active
    // statement per connection and would otherwise invalidate the outer result set.
    List<UserRow> rows =
        db.sql(
                "select * from ftp_user where service_group_id=:g and status='ENABLED' order by"
                    + " username,id")
            .param("g", group)
            .query((rs, rowNumber) -> userRow(rs))
            .list();
    return rows.stream().map(this::snapshot).toList();
  }

  private String sourceHash(String group, List<UserSnapshot> users) throws Exception {
    List<SnapshotUserSource> canonicalUsers =
        users.stream()
            .map(
                user ->
                    new SnapshotUserSource(
                        user.id(),
                        user.username(),
                        user.passwordHash(),
                        user.sshPublicKeys().stream().sorted().toList(),
                        user.department(),
                        user.businessDomain(),
                        user.serviceGroupId(),
                        user.status(),
                        user.expiresAt(),
                        user.directories().stream()
                            .sorted(
                                Comparator.comparing(DirectoryGrant::virtualPath)
                                    .thenComparing(DirectoryGrant::hdfsPath)
                                    .thenComparing(grant -> grant.accessMode().name()))
                            .toList(),
                        user.trafficPolicy()))
            .toList();
    byte[] source = mapper.writeValueAsBytes(new SnapshotSource(group, canonicalUsers));
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
  }

  SignedSnapshotEnvelope latest(String group) {
    return db.sql(
            "select payload_json,payload_sha256,signature from config_snapshot where"
                + " service_group_id=:g and status='PUBLISHED' order by version desc limit 1")
        .param("g", group)
        .query(
            (rs, rowNumber) ->
                new SignedSnapshotEnvelope(rs.getString(1), rs.getString(2), rs.getString(3)))
        .optional()
        .orElseThrow(() -> new NoSuchElementException("No published snapshot"));
  }

  long version(SignedSnapshotEnvelope envelope) {
    try {
      return mapper.readValue(envelope.payloadJson(), SnapshotPayload.class).version();
    } catch (Exception exception) {
      throw new IllegalStateException("Invalid stored snapshot", exception);
    }
  }

  private UserSnapshot snapshot(UserRow row) {
    List<DirectoryGrant> grants =
        db.sql(
                "select"
                    + " d.virtual_path,d.hdfs_path,g.access_mode,d.namespace_quota,d.space_quota_bytes"
                    + " from directory_grant g join directory_mapping d on"
                    + " d.id=g.directory_mapping_id join service_group s on s.id=:group where"
                    + " g.user_id=:u and d.status='ENABLED' and d.hdfs_cluster_id=s.hdfs_cluster_id"
                    + " order by d.virtual_path,d.hdfs_path,g.access_mode")
            .param("group", row.serviceGroupId())
            .param("u", row.id())
            .query(
                (rs, rowNumber) ->
                    new DirectoryGrant(
                        rs.getString(1),
                        rs.getString(2),
                        AccessMode.valueOf(rs.getString(3)),
                        rs.getLong(4),
                        rs.getLong(5)))
            .list();
    Set<String> publicKeys =
        new TreeSet<>(
            db.sql("select public_key from ssh_public_key where user_id=:u order by public_key")
                .param("u", row.id())
                .query(String.class)
                .list());
    TrafficPolicy trafficPolicy =
        db.sql("select * from traffic_policy where user_id=:u")
            .param("u", row.id())
            .query(
                (rs, rowNumber) ->
                    new TrafficPolicy(
                        rs.getLong("upload_bytes_per_second"),
                        rs.getLong("download_bytes_per_second"),
                        rs.getLong("upload_burst_bytes"),
                        rs.getLong("download_burst_bytes"),
                        rs.getInt("max_connections"),
                        rs.getInt("max_upload_transfers"),
                        rs.getInt("max_download_transfers"),
                        rs.getLong("period_upload_files"),
                        rs.getLong("period_download_files"),
                        rs.getLong("period_upload_bytes"),
                        rs.getLong("period_download_bytes"),
                        TrafficPolicy.Period.valueOf(rs.getString("period")),
                        rs.getString("time_zone")))
            .optional()
            .orElse(TrafficPolicy.unlimited());
    return new UserSnapshot(
        row.id(),
        row.username(),
        row.passwordHash(),
        publicKeys,
        row.department(),
        row.businessDomain(),
        row.serviceGroupId(),
        row.status(),
        row.expiresAt(),
        grants,
        trafficPolicy);
  }

  private static UserRow userRow(ResultSet rs) throws SQLException {
    var expiresAt = rs.getTimestamp("expires_at");
    return new UserRow(
        uuid(rs.getObject("id")),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getString("department"),
        rs.getString("business_domain"),
        rs.getString("service_group_id"),
        AccountStatus.valueOf(rs.getString("status")),
        expiresAt == null ? null : expiresAt.toInstant());
  }

  private static UUID uuid(Object value) {
    return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
  }

  private PrivateKey privateKey() throws Exception {
    return KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
  }

  private record UserRow(
      UUID id,
      String username,
      String passwordHash,
      String department,
      String businessDomain,
      String serviceGroupId,
      AccountStatus status,
      Instant expiresAt) {}

  private record SnapshotSource(String serviceGroupId, List<SnapshotUserSource> users) {}

  private record SnapshotUserSource(
      UUID id,
      String username,
      String passwordHash,
      List<String> sshPublicKeys,
      String department,
      String businessDomain,
      String serviceGroupId,
      AccountStatus status,
      Instant expiresAt,
      List<DirectoryGrant> directories,
      TrafficPolicy trafficPolicy) {}
}
