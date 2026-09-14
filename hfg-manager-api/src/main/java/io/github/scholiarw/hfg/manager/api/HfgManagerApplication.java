package io.github.scholiarw.hfg.manager.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.scholiarw.hfg")
@EntityScan(basePackages = "io.github.scholiarw.hfg")
@EnableJpaRepositories(basePackages = "io.github.scholiarw.hfg.manager.infra")
@EnableScheduling
public class HfgManagerApplication {
  public static void main(String[] args) {
    SpringApplication.run(HfgManagerApplication.class, args);
  }
}
