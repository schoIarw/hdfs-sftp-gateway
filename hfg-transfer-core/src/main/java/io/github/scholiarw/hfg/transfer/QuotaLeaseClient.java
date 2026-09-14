package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.*;
import java.util.UUID;

public interface QuotaLeaseClient {
  Lease reserve(UserSnapshot user, TransferDirection direction, long files, long bytes);

  default void renew(Lease lease) {}

  void commit(Lease lease, long completedFiles, long completedBytes);

  record Lease(UUID id, long files, long bytes) {}

  static QuotaLeaseClient noop() {
    return new QuotaLeaseClient() {
      public Lease reserve(UserSnapshot u, TransferDirection d, long f, long b) {
        return new Lease(UUID.randomUUID(), f, b);
      }

      public void commit(Lease l, long f, long b) {}
    };
  }
}
