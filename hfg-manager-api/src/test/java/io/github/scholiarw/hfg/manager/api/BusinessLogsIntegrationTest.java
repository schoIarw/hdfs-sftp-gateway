package io.github.scholiarw.hfg.manager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@EnabledIfEnvironmentVariable(named = "HFG_TEST_DB_URL", matches = ".+")
@SpringBootTest(
    properties = {"hfg.rpc.enabled=false", "hfg.security.admin.password=test-admin-password"})
class BusinessLogsIntegrationTest {
  @Autowired JdbcClient management;
  @Autowired LogsStore logs;
  @Autowired DatabaseDialect dialect;
  @Autowired BusinessLogService businessLogs;
  @Autowired QuotaReservationService quotas;
  @Autowired ObjectMapper objectMapper;

  @Test
  void persistsTransferLifecycleAndQuotaSnapshots() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String cluster = "cluster-" + suffix;
    String group = "group-" + suffix;
    UUID user = UUID.randomUUID();
    Instant now = Instant.ofEpochMilli(System.currentTimeMillis());

    management
        .sql(
            "insert into"
                + " hdfs_cluster(id,name,default_fs,kerberos_enabled,status,created_at,updated_at) "
                + "values(:id,:name,'hdfs://test',false,'ENABLED',:now,:now)")
        .param("id", cluster)
        .param("name", cluster)
        .param("now", java.sql.Timestamp.from(now))
        .update();
    management
        .sql(
            "insert into service_group(id,name,vip,hdfs_cluster_id,status,created_at,updated_at) "
                + "values(:id,:name,:vip,:cluster,'ENABLED',:now,:now)")
        .param("id", group)
        .param("name", group)
        .param("vip", "127.0.0." + (Math.abs(suffix.hashCode()) % 200 + 1))
        .param("cluster", cluster)
        .param("now", java.sql.Timestamp.from(now))
        .update();
    management
        .sql(
            "insert into"
                + " ftp_user(id,username,password_hash,status,service_group_id,created_at,updated_at)"
                + " values(:id,:username,'hash','ENABLED',:group,:now,:now)")
        .param("id", databaseId(user))
        .param("username", "user-" + suffix)
        .param("group", group)
        .param("now", java.sql.Timestamp.from(now))
        .update();
    management
        .sql(
            "insert into traffic_policy(user_id,period,time_zone,updated_at) "
                + "values(:id,'DAY','UTC',:now)")
        .param("id", databaseId(user))
        .param("now", java.sql.Timestamp.from(now))
        .update();

    UUID transfer = UUID.randomUUID();
    Instant started = now.minusSeconds(2);
    businessLogs.ingest(
        new TransferEvent(
            transfer,
            user,
            Protocol.SFTP,
            TransferDirection.UPLOAD,
            TransferStatus.STARTED,
            "/in/report.csv",
            0,
            started,
            "gateway-1",
            "127.0.0.1",
            null,
            "test-" + suffix));
    businessLogs.ingest(
        new TransferEvent(
            transfer,
            user,
            Protocol.SFTP,
            TransferDirection.UPLOAD,
            TransferStatus.COMPLETED,
            "/in/report.csv",
            4096,
            now,
            "gateway-1",
            "127.0.0.1",
            null,
            "test-" + suffix));

    Map<String, Object> transferLog =
        logs.jdbc()
            .sql("select * from logs where record_type='TRANSFER' and transfer_id=:id")
            .param("id", logs.id(transfer))
            .query()
            .singleRow();
    assertThat(transferLog.get("status")).isEqualTo("COMPLETED");
    assertThat(transferLog.get("file_name")).isEqualTo("report.csv");
    assertThat(((Number) transferLog.get("file_size_bytes")).longValue()).isEqualTo(4096);
    assertThat(((Number) transferLog.get("duration_millis")).longValue()).isEqualTo(2000);
    assertThat(((Number) transferLog.get("average_bytes_per_second")).longValue()).isEqualTo(2048);

    var reservation = quotas.reserve(user, TransferDirection.UPLOAD, 1, 4096);
    quotas.commit(reservation.id(), 1, 4096);

    management
        .sql("update traffic_policy set period_download_bytes=10 where user_id=:id")
        .param("id", databaseId(user))
        .update();
    var partial = quotas.reserve(user, TransferDirection.DOWNLOAD, 0, 64L * 1024 * 1024);
    assertThat(partial.bytes()).isEqualTo(10);
    org.junit.jupiter.api.Assertions.assertThrows(
        HfgException.class, () -> quotas.reserve(user, TransferDirection.DOWNLOAD, 0, 1));
    quotas.commit(partial.id(), 0, 10);
    Long snapshots =
        logs.jdbc()
            .sql("select count(*) from logs where record_type='QUOTA' and user_id=:id")
            .param("id", logs.id(user))
            .query(Long.class)
            .single();
    assertThat(snapshots).isGreaterThanOrEqualTo(2);

    var generator = KeyPairGenerator.getInstance("Ed25519");
    String privateKey =
        Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    var publisher = new SnapshotPublisher(management, dialect, objectMapper, privateKey);
    assertThat(publisher.publishIfChanged(group, "integration-test")).isPresent();
    assertThat(publisher.publishIfChanged(group, "integration-test")).isEmpty();
    assertThat(publisher.publish(group, "integration-test")).isNotNull();
    Long publishedVersions =
        management
            .sql("select count(*) from config_snapshot where service_group_id=:group")
            .param("group", group)
            .query(Long.class)
            .single();
    assertThat(publishedVersions).isEqualTo(2);
  }

  private Object databaseId(UUID id) {
    return dialect.id(id);
  }
}
