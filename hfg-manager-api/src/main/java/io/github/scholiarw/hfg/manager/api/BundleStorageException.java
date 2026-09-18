package io.github.scholiarw.hfg.manager.api;

/**
 * Raised when the Manager cannot read or write the HDFS bundle directory. The message is written
 * for operators and is returned verbatim to the UI so that both the failing path and the operating
 * system reason are visible.
 */
class BundleStorageException extends RuntimeException {
  BundleStorageException(String message) {
    super(message);
  }

  BundleStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
