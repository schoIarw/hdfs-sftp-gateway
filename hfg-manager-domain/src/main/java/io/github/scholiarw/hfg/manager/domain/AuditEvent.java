package io.github.scholiarw.hfg.manager.domain;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
    UUID id,
    String actor,
    String action,
    String resourceType,
    String resourceId,
    String correlationId,
    String sourceAddress,
    String beforeJson,
    String afterJson,
    Instant occurredAt) {}
