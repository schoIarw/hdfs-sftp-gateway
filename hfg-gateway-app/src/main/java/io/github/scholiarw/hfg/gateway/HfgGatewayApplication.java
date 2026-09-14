package io.github.scholiarw.hfg.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.scholiarw.hfg")
@EnableConfigurationProperties(GatewayProperties.class)
@EnableScheduling
public class HfgGatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(HfgGatewayApplication.class, args);
  }
}
