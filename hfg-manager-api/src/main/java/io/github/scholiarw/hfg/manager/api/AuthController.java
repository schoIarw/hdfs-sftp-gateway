package io.github.scholiarw.hfg.manager.api;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  @GetMapping("/me")
  AuthenticatedAdmin currentAdmin(Authentication authentication) {
    List<String> roles =
        authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .sorted()
            .toList();
    return new AuthenticatedAdmin(authentication.getName(), roles);
  }

  record AuthenticatedAdmin(String username, List<String> roles) {}
}
