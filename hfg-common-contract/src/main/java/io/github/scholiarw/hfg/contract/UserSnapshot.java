package io.github.scholiarw.hfg.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserSnapshot(
    UUID id,
    String username,
    String passwordHash,
    Set<String> sshPublicKeys,
    String department,
    String businessDomain,
    String serviceGroupId,
    String hdfsEffectiveUser,
    AccountStatus status,
    Instant expiresAt,
    List<DirectoryGrant> directories,
    TrafficPolicy trafficPolicy) {
  public UserSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(status, "status");
    sshPublicKeys = sshPublicKeys == null ? Set.of() : Set.copyOf(sshPublicKeys);
    directories = directories == null ? List.of() : List.copyOf(directories);
    trafficPolicy = trafficPolicy == null ? TrafficPolicy.unlimited() : trafficPolicy;
  }

  public boolean canLoginAt(Instant now) {
    return status == AccountStatus.ENABLED && (expiresAt == null || expiresAt.isAfter(now));
  }
}
