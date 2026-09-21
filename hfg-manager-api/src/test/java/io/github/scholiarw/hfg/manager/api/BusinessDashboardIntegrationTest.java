package io.github.scholiarw.hfg.manager.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.scholiarw.hfg.contract.Protocol;
import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.TransferEvent;
import io.github.scholiarw.hfg.contract.TransferStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@EnabledIfEnvironmentVariable(named = "HFG_TEST_DB_URL", matches = ".+")
@SpringBootTest(
    properties = {"hfg.rpc.enabled=false", "hfg.security.admin.password=test-admin-password"})
class BusinessDashboardIntegrationTest {
  @Autowired JdbcClient db;
  @Autowired DatabaseDialect dialect;
  @Autowired BusinessLogService logs;
  @Autowired DashboardController dashboard;

  @Test
  void separatesOverallMetricsFromPerUserSeriesAndSupportsFiltering() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String cluster = "metrics-cluster-" + suffix;
    String group = "metrics-group-" + suffix;
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Instant now = Instant.now();
    insertClusterAndUsers(cluster, group, suffix, first, second, now);
    transfer(first, "metrics-first-" + suffix, 1024, now.minusSeconds(5));
    transfer(second, "metrics-second-" + suffix, 2048, now.minusSeconds(4));

    var allUsers = dashboard.userHistory(now.minusSeconds(60), now.plusSeconds(1), "minute", null);
    assertThat(allUsers)
        .filteredOn(row -> String.valueOf(row.get("username")).contains(suffix))
        .extracting(row -> String.valueOf(row.get("username")))
        .containsExactlyInAnyOrder("metrics-first-" + suffix, "metrics-second-" + suffix);

    var selected =
        dashboard.userHistory(now.minusSeconds(60), now.plusSeconds(1), "minute", second);
    assertThat(selected)
        .extracting(row -> String.valueOf(row.get("username")))
        .containsOnly("metrics-second-" + suffix);

    var overall = dashboard.allHistory(now.minusSeconds(60), now.plusSeconds(1), "minute");
    assertThat(overall)
        .allSatisfy(row -> assertThat(row).doesNotContainKeys("user_id", "username"));
  }

  private void transfer(UUID user, String username, long bytes, Instant completed) {
    UUID transfer = UUID.randomUUID();
    logs.ingest(
        new TransferEvent(
            transfer,
            user,
            Protocol.SFTP,
            TransferDirection.UPLOAD,
            TransferStatus.STARTED,
            "/in/" + username + ".bin",
            0,
            completed.minusSeconds(1),
            "gateway-test",
            "127.0.0.1",
            null,
            "metrics-test"));
    logs.ingest(
        new TransferEvent(
            transfer,
            user,
            Protocol.SFTP,
            TransferDirection.UPLOAD,
            TransferStatus.COMPLETED,
            "/in/" + username + ".bin",
            bytes,
            completed,
            "gateway-test",
            "127.0.0.1",
            null,
            "metrics-test"));
  }

  private void insertClusterAndUsers(
      String cluster, String group, String suffix, UUID first, UUID second, Instant now) {
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
        .param("vip", "198.51.100." + (Math.abs(suffix.hashCode()) % 200 + 1))
        .param("cluster", cluster)
        .param("now", java.sql.Timestamp.from(now))
        .update();
    insertUser(first, "metrics-first-" + suffix, group, now);
    insertUser(second, "metrics-second-" + suffix, group, now);
  }

  private void insertUser(UUID id, String username, String group, Instant now) {
    db.sql(
            "insert into ftp_user(id,username,password_hash,status,service_group_id,created_at,updated_at) values(:id,:username,'hash','ENABLED',:group,:now,:now)")
        .param("id", dialect.id(id))
        .param("username", username)
        .param("group", group)
        .param("now", java.sql.Timestamp.from(now))
        .update();
  }
}
