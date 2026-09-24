package io.github.scholiarw.hfg.storage;

import java.io.IOException;
import java.util.List;

public interface StorageClient extends AutoCloseable {
  /** Keeps a pooled client alive across a multi-step operation such as upload close + rename. */
  default Lease retain() throws IOException {
    return () -> {};
  }

  interface Lease extends AutoCloseable {
    @Override
    void close() throws IOException;
  }

  StorageEntry stat(String absolutePath) throws IOException;

  List<StorageEntry> list(String absolutePath, String pageToken, int pageSize) throws IOException;

  StorageReadHandle openRead(String absolutePath, long offset) throws IOException;

  StorageWriteHandle create(String absolutePath, boolean overwrite) throws IOException;

  StorageWriteHandle append(String absolutePath) throws IOException;

  boolean mkdirs(String absolutePath) throws IOException;

  boolean delete(String absolutePath, boolean recursive) throws IOException;

  boolean rename(String sourceAbsolutePath, String targetAbsolutePath) throws IOException;

  /** Atomically replaces a file at the target; unsupported storage must fail closed. */
  default void renameReplace(String sourceAbsolutePath, String targetAbsolutePath)
      throws IOException {
    throw new IOException("Atomic replacement is not supported by this storage client");
  }

  QuotaUsage quota(String absolutePath) throws IOException;

  void setQuota(String absolutePath, long namespaceQuota, long spaceQuotaBytes) throws IOException;

  boolean exists(String absolutePath) throws IOException;

  @Override
  void close() throws IOException;
}
