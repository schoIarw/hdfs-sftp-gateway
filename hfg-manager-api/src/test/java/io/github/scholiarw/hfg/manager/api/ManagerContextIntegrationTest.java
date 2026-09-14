package io.github.scholiarw.hfg.manager.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@EnabledIfEnvironmentVariable(named = "HFG_TEST_DB_URL", matches = ".+")
@SpringBootTest(
    properties = {"hfg.rpc.enabled=false", "hfg.security.admin.password=test-admin-password"})
class ManagerContextIntegrationTest {
  @Test
  void startsWithFlywayManagedPostgresqlSchema() {}
}
