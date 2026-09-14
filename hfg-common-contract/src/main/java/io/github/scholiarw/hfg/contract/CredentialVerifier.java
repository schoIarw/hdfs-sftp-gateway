package io.github.scholiarw.hfg.contract;

@FunctionalInterface
public interface CredentialVerifier {
  boolean matches(CharSequence presentedSecret, String storedHash);
}
