package io.github.scholiarw.hfg.manager.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.github.scholiarw.hfg")
@EnableScheduling
public class HfgManagerApplication {
  public static void main(String[] args) {
    SpringApplication.run(HfgManagerApplication.class, args);
  }
}
