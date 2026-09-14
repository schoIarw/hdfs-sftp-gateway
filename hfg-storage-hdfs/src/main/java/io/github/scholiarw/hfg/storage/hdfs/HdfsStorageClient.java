package io.github.scholiarw.hfg.storage.hdfs;

import io.github.scholiarw.hfg.storage.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.hadoop.fs.*;

public final class HdfsStorageClient implements StorageClient {
  private final FileSystem fileSystem;

  public HdfsStorageClient(FileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  @Override
  public StorageEntry stat(String absolutePath) throws IOException {
    return entry(fileSystem.getFileStatus(path(absolutePath)));
  }

  @Override
  public List<StorageEntry> list(String absolutePath, String pageToken, int pageSize)
      throws IOException {
    if (pageSize < 1 || pageSize > 10_000)
      throw new IllegalArgumentException("pageSize must be between 1 and 10000");
    var result = new ArrayList<StorageEntry>(pageSize);
    RemoteIterator<LocatedFileStatus> iterator = fileSystem.listLocatedStatus(path(absolutePath));
    while (iterator.hasNext()) result.add(entry(iterator.next()));
    result.sort(Comparator.comparing(StorageEntry::name));
    return result.stream()
        .filter(e -> pageToken == null || e.name().compareTo(pageToken) > 0)
        .limit(pageSize)
        .toList();
  }

  @Override
  public StorageReadHandle openRead(String absolutePath, long offset) throws IOException {
    FSDataInputStream input = fileSystem.open(path(absolutePath));
    try {
      input.seek(offset);
    } catch (IOException e) {
      input.close();
      throw e;
    }
    return new StorageReadHandle() {
      @Override
      public int read(ByteBuffer target) throws IOException {
        if (!target.hasRemaining()) return 0;
        byte[] buffer = new byte[Math.min(target.remaining(), 64 * 1024)];
        int count = input.read(buffer);
        if (count > 0) target.put(buffer, 0, count);
        return count;
      }

      @Override
      public void seek(long newOffset) throws IOException {
        input.seek(newOffset);
      }

      @Override
      public long position() throws IOException {
        return input.getPos();
      }

      @Override
      public void close() throws IOException {
        input.close();
      }
    };
  }

  @Override
  public StorageWriteHandle create(String absolutePath, boolean overwrite) throws IOException {
    return writeHandle(fileSystem.create(path(absolutePath), overwrite));
  }

  @Override
  public StorageWriteHandle append(String absolutePath) throws IOException {
    return writeHandle(fileSystem.append(path(absolutePath)));
  }

  private static StorageWriteHandle writeHandle(FSDataOutputStream output) {
    return new StorageWriteHandle() {
      private long position = output.getPos();

      @Override
      public int write(ByteBuffer source) throws IOException {
        int total = source.remaining();
        if (source.hasArray()) {
          int offset = source.arrayOffset() + source.position();
          output.write(source.array(), offset, total);
          source.position(source.limit());
        } else {
          byte[] buffer = new byte[Math.min(total, 64 * 1024)];
          while (source.hasRemaining()) {
            int n = Math.min(source.remaining(), buffer.length);
            source.get(buffer, 0, n);
            output.write(buffer, 0, n);
          }
        }
        position += total;
        return total;
      }

      @Override
      public void flush() throws IOException {
        output.hflush();
      }

      @Override
      public long position() {
        return position;
      }

      @Override
      public void close() throws IOException {
        output.close();
      }
    };
  }

  @Override
  public boolean mkdirs(String absolutePath) throws IOException {
    return fileSystem.mkdirs(path(absolutePath));
  }

  @Override
  public boolean delete(String absolutePath, boolean recursive) throws IOException {
    return fileSystem.delete(path(absolutePath), recursive);
  }

  @Override
  public boolean rename(String sourceAbsolutePath, String targetAbsolutePath) throws IOException {
    return fileSystem.rename(path(sourceAbsolutePath), path(targetAbsolutePath));
  }

  @Override
  public io.github.scholiarw.hfg.storage.QuotaUsage quota(String absolutePath) throws IOException {
    ContentSummary q = fileSystem.getContentSummary(path(absolutePath));
    return new io.github.scholiarw.hfg.storage.QuotaUsage(
        q.getQuota(), q.getFileAndDirectoryCount(), q.getSpaceQuota(), q.getSpaceConsumed());
  }

  @Override
  public boolean exists(String absolutePath) throws IOException {
    return fileSystem.exists(path(absolutePath));
  }

  @Override
  public void setQuota(String absolutePath, long namespaceQuota, long spaceQuotaBytes)
      throws IOException {
    if (!(fileSystem instanceof org.apache.hadoop.hdfs.DistributedFileSystem dfs))
      throw new UnsupportedOperationException("Quotas require DistributedFileSystem");
    long ns =
        namespaceQuota < 0
            ? org.apache.hadoop.hdfs.protocol.HdfsConstants.QUOTA_DONT_SET
            : namespaceQuota;
    long space =
        spaceQuotaBytes < 0
            ? org.apache.hadoop.hdfs.protocol.HdfsConstants.QUOTA_DONT_SET
            : spaceQuotaBytes;
    dfs.setQuota(path(absolutePath), ns, space);
  }

  @Override
  public void close() throws IOException {
    fileSystem.close();
  }

  private static Path path(String value) {
    if (value == null || !value.startsWith("/"))
      throw new IllegalArgumentException("HDFS path must be absolute");
    return new Path(value);
  }

  private static StorageEntry entry(FileStatus status) {
    return new StorageEntry(
        status.getPath().toUri().getPath(),
        status.getPath().getName(),
        status.isDirectory(),
        status.getLen(),
        Instant.ofEpochMilli(status.getModificationTime()),
        status.getOwner(),
        status.getGroup(),
        status.getPermission().toString());
  }
}
