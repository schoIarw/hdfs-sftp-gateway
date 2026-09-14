package io.github.scholiarw.hfg.manager.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
final class LogsStore {
  private static final DateTimeFormatter PARTITION_DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private final DataSource dataSource;
  private final HikariDataSource ownedDataSource;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final DatabaseDialect.Vendor vendor;
  private final int retentionDays;
  private final int precreateDays;

  LogsStore(
      DataSource mainDataSource,
      @Value("${hfg.logs.url:}") String url,
      @Value("${hfg.logs.username:}") String username,
      @Value("${hfg.logs.password:}") String password,
      @Value("${hfg.logs.pool-size:10}") int poolSize,
      @Value("${hfg.logs.retention-days:180}") int retentionDays,
      @Value("${hfg.logs.precreate-days:7}") int precreateDays) {
    if (poolSize < 1 || retentionDays < 1 || precreateDays < 1) {
      throw new IllegalArgumentException(
          "Logs pool size, retention and precreate days must be positive");
    }
    if (StringUtils.hasText(url)) {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(url);
      config.setUsername(username);
      config.setPassword(password);
      config.setPoolName("hfg-logs");
      config.setMaximumPoolSize(poolSize);
      config.setMinimumIdle(1);
      this.ownedDataSource = new HikariDataSource(config);
      this.dataSource = ownedDataSource;
    } else {
      this.ownedDataSource = null;
      this.dataSource = mainDataSource;
    }
    this.vendor = detect(dataSource);
    this.jdbc = JdbcClient.create(dataSource);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.retentionDays = retentionDays;
    this.precreateDays = precreateDays;
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/logs/" + vendor.name().toLowerCase(Locale.ROOT))
        .table("flyway_logs_schema_history")
        .load()
        .migrate();
    maintainPartitions();
  }

  JdbcClient jdbc() {
    return jdbc;
  }

  DatabaseDialect.Vendor vendor() {
    return vendor;
  }

  <T> T transaction(Function<JdbcClient, T> work) {
    return transactions.execute(status -> work.apply(jdbc));
  }

  @Scheduled(cron = "${hfg.logs.maintenance-cron:0 10 0 * * *}", zone = "UTC")
  void maintainPartitions() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (vendor == DatabaseDialect.Vendor.POSTGRESQL) {
      for (int offset = 0; offset <= precreateDays; offset++)
        createPostgres(today.plusDays(offset));
    } else {
      for (int offset = -1; offset <= precreateDays; offset++) createMysql(today.plusDays(offset));
    }
    dropExpired(today.minusDays(retentionDays));
  }

  private void createPostgres(LocalDate day) {
    String name = partitionName(day);
    jdbc.sql(
            "create table if not exists "
                + name
                + " partition of logs for values from ('"
                + day
                + "') to ('"
                + day.plusDays(1)
                + "')")
        .update();
  }

  private void createMysql(LocalDate day) {
    String name = "p" + PARTITION_DATE.format(day);
    if (mysqlPartitions().contains(name)) return;
    String ddl =
        "alter table logs reorganize partition p_future into (partition "
            + name
            + " values less than ('"
            + day.plusDays(1)
            + "'), partition p_future values less than (maxvalue))";
    try {
      jdbc.sql(ddl).update();
    } catch (DataAccessException race) {
      if (!mysqlPartitions().contains(name)) throw race;
    }
  }

  private Set<String> mysqlPartitions() {
    return new HashSet<>(
        jdbc.sql(
                "select partition_name from information_schema.partitions "
                    + "where table_schema=database() and table_name='logs' and partition_name is not null")
            .query(String.class)
            .list());
  }

  private void dropExpired(LocalDate cutoff) {
    if (vendor == DatabaseDialect.Vendor.POSTGRESQL) {
      List<String> names =
          jdbc.sql(
                  "select tablename from pg_tables where schemaname=current_schema() and tablename like 'logs_%'")
              .query(String.class)
              .list();
      for (String name : names) {
        LocalDate day = parsePartition(name, "logs_");
        if (day != null && day.isBefore(cutoff)) jdbc.sql("drop table if exists " + name).update();
      }
      return;
    }
    for (String name : mysqlPartitions()) {
      LocalDate day = parsePartition(name, "p");
      if (day != null && day.isBefore(cutoff)) {
        try {
          jdbc.sql("alter table logs drop partition " + name).update();
        } catch (DataAccessException race) {
          if (mysqlPartitions().contains(name)) throw race;
        }
      }
    }
  }

  private static String partitionName(LocalDate day) {
    return "logs_" + PARTITION_DATE.format(day);
  }

  private static LocalDate parsePartition(String name, String prefix) {
    if (!name.startsWith(prefix) || name.length() != prefix.length() + 8) return null;
    try {
      return LocalDate.parse(name.substring(prefix.length()), PARTITION_DATE);
    } catch (java.time.format.DateTimeParseException ignored) {
      return null;
    }
  }

  private static DatabaseDialect.Vendor detect(DataSource source) {
    try (var connection = source.getConnection()) {
      String name = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (name.contains("postgresql")) return DatabaseDialect.Vendor.POSTGRESQL;
      if (name.contains("mysql")) return DatabaseDialect.Vendor.MYSQL;
      throw new IllegalStateException("Unsupported logs database: " + name);
    } catch (java.sql.SQLException exception) {
      throw new IllegalStateException("Cannot detect logs database", exception);
    }
  }

  @PreDestroy
  void close() {
    if (ownedDataSource != null) ownedDataSource.close();
  }
}
