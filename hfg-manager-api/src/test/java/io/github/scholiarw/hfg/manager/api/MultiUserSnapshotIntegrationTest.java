package io.github.scholiarw.hfg.manager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.AccessMode;
import io.github.scholiarw.hfg.contract.SnapshotPayload;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@EnabledIfEnvironmentVariable(named = "HFG_TEST_DB_URL", matches = ".+")
@SpringBootTest(
    properties = {"hfg.rpc.enabled=false", "hfg.security.admin.password=test-admin-password"})
class MultiUserSnapshotIntegrationTest {
  @Autowired JdbcClient db;
  @Autowired DatabaseDialect dialect;
  @Autowired ObjectMapper mapper;

  @Test
  void publishesBothUsersWithOnlyTheirOwnedDirectory() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String cluster = "multi-cluster-" + suffix;
    String group = "multi-group-" + suffix;
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Instant now = Instant.now();
    insertClusterAndGroup(cluster, group, suffix, now);
    insertUser(first, "first-" + suffix, "hash-first", group, now);
    insertUser(second, "second-" + suffix, "hash-second", group, now);
    // Every user has an independent virtual namespace, so both must be allowed to own root.
    insertDirectory(first, cluster, "/", "/data/first-" + suffix, "READ_ONLY", now);
    insertDirectory(second, cluster, "/", "/data/second-" + suffix, "READ_WRITE", now);

    var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var publisher =
        new SnapshotPublisher(
            db,
            dialect,
            mapper,
            Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
    SnapshotPayload payload =
        mapper.readValue(
            publisher.publish(group, "integration-test").payloadJson(), SnapshotPayload.class);

    assertThat(payload.users()).hasSize(2);
    var firstSnapshot =
        payload.users().stream().filter(user -> user.id().equals(first)).findFirst().orElseThrow();
    var secondSnapshot =
        payload.users().stream().filter(user -> user.id().equals(second)).findFirst().orElseThrow();
    assertThat(firstSnapshot.passwordHash()).isEqualTo("hash-first");
    assertThat(firstSnapshot.directories()).hasSize(1);
    assertThat(firstSnapshot.directories().get(0).virtualPath()).isEqualTo("/");
    assertThat(firstSnapshot.directories().get(0).accessMode()).isEqualTo(AccessMode.READ_ONLY);
    assertThat(secondSnapshot.passwordHash()).isEqualTo("hash-second");
    assertThat(secondSnapshot.directories()).hasSize(1);
    assertThat(secondSnapshot.directories().get(0).virtualPath()).isEqualTo("/");
    assertThat(secondSnapshot.directories().get(0).accessMode()).isEqualTo(AccessMode.READ_WRITE);
  }

  private void insertClusterAndGroup(String cluster, String group, String suffix, Instant now) {
    db.sql(
            "insert into hdfs_cluster(id,name,default_fs,kerberos_enabled,status,created_at,updated_at) values(:id,:name,'hdfs://test',false,'ENABLED',:now,:now)")
        .param("id", cluster)
        .param("name", cluster)
        .param("now", java.sql.Timestamp.from(now))
        .update();
    db.sql(
            "insert into service_group(id,name,vip,hdfs_cluster_id,status,created_at,updated_at) values(:id,:name,:vip,:cluster,'ENABLED',:now,:now)")
        .param("id", group)
        .param("name", group)
        .param("vip", "192.0.2." + (Math.abs(suffix.hashCode()) % 200 + 1))
        .param("cluster", cluster)
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }

  private void insertUser(UUID id, String username, String hash, String group, Instant now) {
    db.sql(
            "insert into ftp_user(id,username,password_hash,status,service_group_id,created_at,updated_at) values(:id,:username,:hash,'ENABLED',:group,:now,:now)")
        .param("id", dialect.id(id))
        .param("username", username)
        .param("hash", hash)
        .param("group", group)
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }

  private void insertDirectory(
      UUID user,
      String cluster,
      String virtualPath,
      String hdfsPath,
      String accessMode,
      Instant now) {
    db.sql(
            "insert into directory_mapping(id,name,virtual_path,hdfs_path,hdfs_cluster_id,auto_create,namespace_quota,space_quota_bytes,status,provisioning_status,owner_user_id,access_mode,created_at,updated_at) values(:id,:name,:virtual,:hdfs,:cluster,false,100,1048576,'ENABLED','MANUAL',:user,:mode,:now,:now)")
        .param("id", dialect.id(UUID.randomUUID()))
        .param("name", "root-" + user)
        .param("virtual", virtualPath)
        .param("hdfs", hdfsPath)
        .param("cluster", cluster)
        .param("user", dialect.id(user))
        .param("mode", accessMode)
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }
}
