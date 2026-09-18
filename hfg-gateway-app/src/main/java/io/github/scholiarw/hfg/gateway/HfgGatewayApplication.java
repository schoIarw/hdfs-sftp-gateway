package io.github.scholiarw.hfg.gateway;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.scholiarw.hfg")
@EnableConfigurationProperties(GatewayProperties.class)
@EnableScheduling
public class HfgGatewayApplication {
  public static void main(String[] args) {
    // Apache SSHD needs a JCE provider that understands Ed25519 for ssh-ed25519 keys
    // (public key authentication and Ed25519 host keys); without BC it logs
    // "EdDSA provider not supported" and rejects such keys.
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
      Security.addProvider(new BouncyCastleProvider());
    SpringApplication.run(HfgGatewayApplication.class, args);
  }
}
