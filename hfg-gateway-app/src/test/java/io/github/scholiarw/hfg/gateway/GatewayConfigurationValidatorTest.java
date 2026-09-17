package io.github.scholiarw.hfg.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayConfigurationValidatorTest {
  @TempDir Path temp;

  @Test
  void acceptsMinimalConfigurationWithDefaultPathsOverriddenForTest() throws Exception {
    GatewayProperties properties = properties("gateway-a", "group-a");
    assertDoesNotThrow(() -> GatewayConfigurationValidator.validate(properties));
  }

  @Test
  void rejectsInvalidServiceGroupWithActionableParameterName() throws Exception {
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> GatewayConfigurationValidator.validate(properties("gateway-a", "bad group")));
    assertTrue(exception.getMessage().contains("HFG_SERVICE_GROUP_ID"));
  }

  @Test
  void rejectsUnreadableCertificateWithConfiguredParameterName() throws Exception {
    GatewayProperties valid = properties("gateway-a", "group-a");
    GatewayProperties.Rpc rpc = valid.rpc();
    GatewayProperties invalid =
        new GatewayProperties(
            valid.gatewayId(),
            valid.serviceGroupId(),
            valid.snapshot(),
            new GatewayProperties.Rpc(
                rpc.host(),
                rpc.port(),
                rpc.serverName(),
                temp.resolve("missing-ca.crt"),
                rpc.clientCertificate(),
                rpc.clientPrivateKey(),
                rpc.advertisedAddress(),
                rpc.managementPort(),
                rpc.heartbeatInterval()),
            valid.hdfs(),
            valid.ftp(),
            valid.sftp());
    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> GatewayConfigurationValidator.validate(invalid));
    assertTrue(exception.getMessage().contains("HFG_RPC_CA"));
  }

  private GatewayProperties properties(String gatewayId, String groupId) throws Exception {
    Path ca = Files.writeString(temp.resolve("ca.crt"), "test");
    Path certificate = Files.writeString(temp.resolve("gateway.crt"), "test");
    Path key = Files.writeString(temp.resolve("gateway.key"), "test");
    String publicKey = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    return new GatewayProperties(
        gatewayId,
        groupId,
        new GatewayProperties.Snapshot(
            temp.resolve("snapshot.json"),
            publicKey,
            temp.resolve("events.wal"),
            Duration.ofSeconds(5)),
        new GatewayProperties.Rpc(
            "manager.example.com",
            19090,
            "manager.example.com",
            ca,
            certificate,
            key,
            "10.0.0.11",
            18080,
            Duration.ofSeconds(10)),
        new GatewayProperties.Hdfs(temp.resolve("hdfs-runtime"), Duration.ofMinutes(1)),
        new GatewayProperties.Ftp(
            true, "0.0.0.0", 21, "30000-31000", false, 300),
        new GatewayProperties.Sftp(
            true, "0.0.0.0", 22, temp.resolve("ssh_host_ed25519_key")));
  }
}
