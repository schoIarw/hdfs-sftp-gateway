package io.github.scholiarw.hfg.manager.infra;

import io.github.scholiarw.hfg.manager.domain.*;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class ManagerDomainConfiguration {
  @Bean
  Clock hfgClock() {
    return Clock.systemUTC();
  }

  @Bean
  UserManagementService userManagementService(
      UserRepository users, PasswordHasher passwords, Clock clock) {
    return new UserManagementService(users, passwords, clock);
  }
}
