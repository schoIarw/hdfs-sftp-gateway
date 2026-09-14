package io.github.scholiarw.hfg.manager.api;

import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
final class DatabaseDialect {
  enum Vendor { POSTGRESQL, MYSQL }
  private final Vendor vendor;

  DatabaseDialect(DataSource dataSource) {
    try (var connection = dataSource.getConnection()) {
      String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
      if (product.contains("postgresql")) vendor = Vendor.POSTGRESQL;
      else if (product.contains("mysql")) vendor = Vendor.MYSQL;
      else throw new IllegalStateException("Unsupported database: " + product);
    } catch (java.sql.SQLException exception) {
      throw new IllegalStateException("Cannot detect database vendor", exception);
    }
  }

  boolean mysql() { return vendor == Vendor.MYSQL; }
  String choose(String postgresql, String mysql) { return mysql() ? mysql : postgresql; }
}
