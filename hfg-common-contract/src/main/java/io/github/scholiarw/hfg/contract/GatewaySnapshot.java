package io.github.scholiarw.hfg.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GatewaySnapshot(
    long version,
    String serviceGroupId,
    Instant generatedAt,
    List<UserSnapshot> users,
    String payloadSha256,
    String signature) {
  public GatewaySnapshot {
    if (version < 1) throw new IllegalArgumentException("version must be positive");
    Objects.requireNonNull(serviceGroupId, "serviceGroupId");
    Objects.requireNonNull(generatedAt, "generatedAt");
    users = users == null ? List.of() : List.copyOf(users);
    Objects.requireNonNull(payloadSha256, "payloadSha256");
    Objects.requireNonNull(signature, "signature");
  }
}
