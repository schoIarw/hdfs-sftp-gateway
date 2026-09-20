package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.TransferDirection;
import io.github.scholiarw.hfg.contract.UserSnapshot;

public interface TransferLimiter {
  Permit open(UserSnapshot user, TransferDirection direction, long initialBytes);

  default Permit open(UserSnapshot user, TransferDirection direction) {
    return open(user, direction, 0);
  }

  interface Permit extends AutoCloseable {
    void acquire(int bytes);

    void complete(boolean success);

    @Override
    default void close() {
      complete(false);
    }
  }

  static TransferLimiter unlimited() {
    return (u, d, initialBytes) ->
        new Permit() {
          public void acquire(int bytes) {}

          public void complete(boolean success) {}
        };
  }
}
