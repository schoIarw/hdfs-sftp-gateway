package io.github.scholiarw.hfg.contract;

import java.util.Objects;

public final class HfgException extends RuntimeException {
  private final HfgErrorCode code;
  private final String correlationId;

  public HfgException(HfgErrorCode code, String message) {
    this(code, message, null, null);
  }

  public HfgException(HfgErrorCode code, String message, String correlationId, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.correlationId = correlationId;
  }

  public HfgErrorCode code() {
    return code;
  }

  public String correlationId() {
    return correlationId;
  }
}
