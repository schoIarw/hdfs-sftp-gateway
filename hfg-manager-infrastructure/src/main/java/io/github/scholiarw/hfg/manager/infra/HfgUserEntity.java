package io.github.scholiarw.hfg.manager.infra;

import io.github.scholiarw.hfg.contract.AccountStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ftp_user")
class HfgUserEntity {
  @Id UUID id;

  @Column(nullable = false, unique = true, length = 64)
  String username;

  @Column(name = "password_hash", nullable = false, length = 255)
  String passwordHash;

  @Column(length = 128)
  String department;

  @Column(name = "business_domain", length = 128)
  String businessDomain;

  @Column(length = 64)
  String phone;

  @Column(length = 255)
  String email;

  @Column(length = 1024)
  String note;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  AccountStatus status;

  @Column(name = "service_group_id", nullable = false, length = 64)
  String serviceGroupId;

  @Column(name = "expires_at")
  Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version
  @Column(nullable = false)
  Long revision;
}
