package io.github.scholiarw.hfg.storage;

import java.io.IOException;

@FunctionalInterface
public interface StorageClientFactory {
  StorageClient forEffectiveUser(String effectiveUser) throws IOException;
}
