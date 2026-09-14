package io.github.scholiarw.hfg.manager.infra;

import io.github.scholiarw.hfg.manager.domain.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class BCryptPasswordHasher implements PasswordHasher {
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public String hash(CharSequence clearText) {
    if (clearText == null || clearText.length() < 12)
      throw new IllegalArgumentException("Password must contain at least 12 characters");
    return encoder.encode(clearText);
  }

  public boolean matches(CharSequence clearText, String hash) {
    return clearText != null && hash != null && encoder.matches(clearText, hash);
  }
}
