package io.github.scholiarw.hfg.manager.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {
  @Bean
  SecurityFilterChain hfgSecurity(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/actuator/**"))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico",
                        "/actuator/health/**",
                        "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }

  @Bean
  UserDetailsService hfgAdmin(
      @Value("${hfg.security.admin.username}") String username,
      @Value("${hfg.security.admin.password}") String password) {
    var encoder = new BCryptPasswordEncoder(12);
    return new InMemoryUserDetailsManager(
        User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
  }

  @Bean
  BCryptPasswordEncoder hfgPasswordEncoder() {
    return new BCryptPasswordEncoder(12);
  }
}
