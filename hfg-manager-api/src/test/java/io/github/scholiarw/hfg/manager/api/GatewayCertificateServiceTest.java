package io.github.scholiarw.hfg.manager.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GatewayCertificateServiceTest {
  @Test
  void createsCompleteGatewayBootstrapEnvironment() throws Exception {
    String publicKey =
        Base64.getEncoder()
            .encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());

    String environment =
        GatewayCertificateService.gatewayEnvironment("gateway-a01", "group-a", publicKey);

    assertTrue(environment.contains("HFG_GATEWAY_ID=gateway-a01\n"));
    assertTrue(environment.contains("HFG_SERVICE_GROUP_ID=group-a\n"));
    assertTrue(environment.contains("HFG_RPC_CA=/etc/hfg/pki/ca.crt\n"));
    assertTrue(environment.contains("HFG_RPC_CLIENT_CERT=/etc/hfg/pki/gateway.crt\n"));
    assertTrue(environment.contains("HFG_RPC_CLIENT_KEY=/etc/hfg/pki/gateway.key\n"));
    assertEquals(
        1,
        environment
            .lines()
            .filter(line -> line.startsWith("HFG_SNAPSHOT_PUBLIC_KEY_BASE64="))
            .count());
    assertTrue(environment.endsWith(publicKey + "\n"));
  }
}
