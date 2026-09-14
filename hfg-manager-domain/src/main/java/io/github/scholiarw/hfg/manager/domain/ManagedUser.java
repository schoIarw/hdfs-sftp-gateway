package io.github.scholiarw.hfg.manager.domain;

import io.github.scholiarw.hfg.contract.AccountStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ManagedUser(
    UUID id,
    String username,
    String passwordHash,
    String department,
    String businessDomain,
    String phone,
    String email,
    String note,
    AccountStatus status,
    String serviceGroupId,
    String hdfsEffectiveUser,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    long revision) {
  public ManagedUser {
    Objects.requireNonNull(id);
    Objects.requireNonNull(username);
    Objects.requireNonNull(status);
    username = username.trim();
    if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))
      throw new IllegalArgumentException("Invalid username");
    if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
  }

  public ManagedUser withStatus(AccountStatus next, Instant now) {
    return new ManagedUser(
        id,
        username,
        passwordHash,
        department,
        businessDomain,
        phone,
        email,
        note,
        next,
        serviceGroupId,
        hdfsEffectiveUser,
        expiresAt,
        createdAt,
        now,
        revision);
  }
}
