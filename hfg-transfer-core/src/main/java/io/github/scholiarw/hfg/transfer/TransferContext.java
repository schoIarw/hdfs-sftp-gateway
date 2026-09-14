package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.Protocol;
import io.github.scholiarw.hfg.contract.UserSnapshot;

public record TransferContext(
    UserSnapshot user,
    Protocol protocol,
    String workingDirectory,
    String gatewayId,
    String clientAddress,
    String correlationId) {}
