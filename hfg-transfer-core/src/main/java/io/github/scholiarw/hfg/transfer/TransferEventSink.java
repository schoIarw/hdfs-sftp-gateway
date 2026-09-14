package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.TransferEvent;

@FunctionalInterface
public interface TransferEventSink {
  void publish(TransferEvent event);

  static TransferEventSink noop() {
    return event -> {};
  }
}
