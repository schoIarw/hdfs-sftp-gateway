package io.github.scholiarw.hfg.manager.domain;

import io.github.scholiarw.hfg.contract.AccountStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

public final class UserManagementService {
  private final UserRepository users;
  private final PasswordHasher passwords;
  private final Clock clock;

  public UserManagementService(UserRepository users, PasswordHasher passwords, Clock clock) {
    this.users = users;
    this.passwords = passwords;
    this.clock = clock;
  }

  public ManagedUser create(CreateUser command) {
    users
        .findByUsername(command.username())
        .ifPresent(
            u -> {
              throw new IllegalArgumentException("Username already exists");
            });
    Instant now = clock.instant();
    return users.save(
        new ManagedUser(
            UUID.randomUUID(),
            command.username(),
            passwords.hash(command.password()),
            command.department(),
            command.businessDomain(),
            command.phone(),
            command.email(),
            command.note(),
            AccountStatus.ENABLED,
            command.serviceGroupId(),
            command.hdfsEffectiveUser(),
            command.expiresAt(),
            now,
            now,
            0));
  }

  public ManagedUser update(UUID id, long expectedRevision, UpdateUser command) {
    ManagedUser old = require(id);
    if (old.revision() != expectedRevision)
      throw new IllegalStateException("User was modified by another administrator");
    Instant now = clock.instant();
    return users.save(
        new ManagedUser(
            old.id(),
            old.username(),
            old.passwordHash(),
            command.department(),
            command.businessDomain(),
            command.phone(),
            command.email(),
            command.note(),
            old.status(),
            command.serviceGroupId(),
            command.hdfsEffectiveUser(),
            command.expiresAt(),
            old.createdAt(),
            now,
            old.revision()));
  }

  public ManagedUser changeStatus(UUID id, long expectedRevision, AccountStatus status) {
    ManagedUser old = require(id);
    if (old.revision() != expectedRevision)
      throw new IllegalStateException("User was modified by another administrator");
    return users.save(old.withStatus(status, clock.instant()));
  }

  public void resetPassword(UUID id, long expectedRevision, String password) {
    ManagedUser old = require(id);
    if (old.revision() != expectedRevision)
      throw new IllegalStateException("User was modified by another administrator");
    users.save(
        new ManagedUser(
            old.id(),
            old.username(),
            passwords.hash(password),
            old.department(),
            old.businessDomain(),
            old.phone(),
            old.email(),
            old.note(),
            old.status(),
            old.serviceGroupId(),
            old.hdfsEffectiveUser(),
            old.expiresAt(),
            old.createdAt(),
            clock.instant(),
            old.revision()));
  }

  public ManagedUser require(UUID id) {
    return users.findById(id).orElseThrow(() -> new NoSuchElementException("User not found"));
  }

  public Page list(int page, int size, String query) {
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    return new Page(
        users.findAll(safePage * safeSize, safeSize, query),
        users.count(query),
        safePage,
        safeSize);
  }

  public void delete(UUID id) {
    require(id);
    users.delete(id);
  }

  public record CreateUser(
      String username,
      String password,
      String department,
      String businessDomain,
      String phone,
      String email,
      String note,
      String serviceGroupId,
      String hdfsEffectiveUser,
      Instant expiresAt) {}

  public record UpdateUser(
      String department,
      String businessDomain,
      String phone,
      String email,
      String note,
      String serviceGroupId,
      String hdfsEffectiveUser,
      Instant expiresAt) {}

  public record Page(List<ManagedUser> items, long total, int page, int size) {}
}
