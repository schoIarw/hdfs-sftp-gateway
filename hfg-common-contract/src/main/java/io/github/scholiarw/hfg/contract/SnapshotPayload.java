package io.github.scholiarw.hfg.contract;

import java.time.Instant;
import java.util.*;

public record SnapshotPayload(
    long version, String serviceGroupId, Instant generatedAt, List<UserSnapshot> users) {
  public SnapshotPayload {
    if (version < 1) throw new IllegalArgumentException("version must be positive");
    Objects.requireNonNull(serviceGroupId);
    Objects.requireNonNull(generatedAt);
    users = users == null ? List.of() : List.copyOf(users);
  }
}
