package io.github.scholiarw.hfg.contract;

import java.util.Objects;

public record SignedSnapshotEnvelope(
    String payloadJson, String payloadSha256, String signatureBase64) {
  public SignedSnapshotEnvelope {
    Objects.requireNonNull(payloadJson);
    Objects.requireNonNull(payloadSha256);
    Objects.requireNonNull(signatureBase64);
  }
}
