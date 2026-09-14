package io.github.scholiarw.hfg.manager.domain;

public interface PasswordHasher {
  String hash(CharSequence clearText);

  boolean matches(CharSequence clearText, String hash);
}
