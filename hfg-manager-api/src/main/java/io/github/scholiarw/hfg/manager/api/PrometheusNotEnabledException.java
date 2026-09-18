package io.github.scholiarw.hfg.manager.api;

/** Raised when the Prometheus integration is switched off by configuration. */
class PrometheusNotEnabledException extends RuntimeException {
  PrometheusNotEnabledException(String message) {
    super(message);
  }
}
