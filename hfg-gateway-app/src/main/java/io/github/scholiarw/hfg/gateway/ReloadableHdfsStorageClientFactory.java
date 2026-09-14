package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.storage.StorageClient;
import io.github.scholiarw.hfg.storage.StorageClientFactory;
import io.github.scholiarw.hfg.storage.hdfs.HdfsStorageClientFactory;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class ReloadableHdfsStorageClientFactory implements StorageClientFactory {
  private final AtomicReference<HdfsStorageClientFactory> delegate = new AtomicReference<>();

  void install(String defaultFs, List<String> resources, String principal, String keytab)
      throws IOException {
    delegate.set(
        new HdfsStorageClientFactory(
            new HdfsStorageClientFactory.Settings(defaultFs, resources, principal, keytab, false)));
  }

  boolean ready() {
    return delegate.get() != null;
  }

  @Override
  public StorageClient forEffectiveUser(String ignored) throws IOException {
    HdfsStorageClientFactory current = delegate.get();
    if (current == null) throw new IOException("HDFS configuration has not been received");
    return current.forEffectiveUser(null);
  }
}
