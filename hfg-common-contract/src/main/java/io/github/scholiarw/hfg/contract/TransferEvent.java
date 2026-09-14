package io.github.scholiarw.hfg.contract;

import java.time.Instant;
import java.util.UUID;

public record TransferEvent(
    UUID transferId,
    UUID userId,
    Protocol protocol,
    TransferDirection direction,
    TransferStatus status,
    String virtualPath,
    long bytes,
    Instant occurredAt,
    String gatewayId,
    String clientAddress,
    HfgErrorCode errorCode,
    String correlationId) {}
