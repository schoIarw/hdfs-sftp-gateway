package io.github.scholiarw.hfg.storage.hdfs;

import io.github.scholiarw.hfg.storage.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.fs.*;

public final class HdfsStorageClient implements StorageClient {
  private static final int IO_BUFFER_BYTES = 64 * 1024;
  private static final int MAX_DIRECTORY_ENTRIES = 10_000;
  private static final ThreadLocal<byte[]> IO_BUFFER =
      ThreadLocal.withInitial(() -> new byte[IO_BUFFER_BYTES]);
  private final FileSystem fileSystem;
  private final boolean closeFileSystem;
  private int openHandles;
  private boolean closeRequested;
  private boolean fileSystemClosed;

  public HdfsStorageClient(FileSystem fileSystem) {
    this(fileSystem, true);
  }

  public HdfsStorageClient(FileSystem fileSystem, boolean closeFileSystem) {
    this.fileSystem = fileSystem;
    this.closeFileSystem = closeFileSystem;
  }

  @Override
  public Lease retain() throws IOException {
    beginHandle();
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) endHandle();
    };
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
    while (iterator.hasNext()) {
      if (result.size() == MAX_DIRECTORY_ENTRIES)
        throw new IOException(
            "Directory contains more than "
                + MAX_DIRECTORY_ENTRIES
                + " entries; refine the directory layout before listing it through HFG");
      result.add(entry(iterator.next()));
    }
    result.sort(Comparator.comparing(StorageEntry::name));
    return result.stream()
        .filter(e -> pageToken == null || e.name().compareTo(pageToken) > 0)
        .limit(pageSize)
        .toList();
  }

  @Override
  public StorageReadHandle openRead(String absolutePath, long offset) throws IOException {
    beginHandle();
    FSDataInputStream opened = null;
    try {
      opened = fileSystem.open(path(absolutePath));
      opened.seek(offset);
    } catch (IOException | RuntimeException e) {
      if (opened != null) {
        try {
          opened.close();
        } catch (IOException closeFailure) {
          e.addSuppressed(closeFailure);
        }
      }
      try {
        endHandle();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    final FSDataInputStream input = opened;
    return new StorageReadHandle() {
      private final AtomicBoolean closed = new AtomicBoolean();

      @Override
      public int read(ByteBuffer target) throws IOException {
        if (!target.hasRemaining()) return 0;
        byte[] buffer = IO_BUFFER.get();
        int count = input.read(buffer, 0, Math.min(target.remaining(), buffer.length));
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
        if (!closed.compareAndSet(false, true)) return;
        try {
          input.close();
        } finally {
          endHandle();
        }
      }
    };
  }

  @Override
  public StorageWriteHandle create(String absolutePath, boolean overwrite) throws IOException {
    beginHandle();
    try {
      return writeHandle(fileSystem.create(path(absolutePath), overwrite));
    } catch (IOException | RuntimeException e) {
      endHandle();
      throw e;
    }
  }

  @Override
  public StorageWriteHandle append(String absolutePath) throws IOException {
    beginHandle();
    try {
      return writeHandle(fileSystem.append(path(absolutePath)));
    } catch (IOException | RuntimeException e) {
      endHandle();
      throw e;
    }
  }

  private StorageWriteHandle writeHandle(FSDataOutputStream output) {
    return new StorageWriteHandle() {
      private final AtomicBoolean closed = new AtomicBoolean();
      private long position = output.getPos();

      @Override
      public int write(ByteBuffer source) throws IOException {
        int total = source.remaining();
        if (source.hasArray()) {
          int offset = source.arrayOffset() + source.position();
          output.write(source.array(), offset, total);
          source.position(source.limit());
        } else {
          byte[] buffer = IO_BUFFER.get();
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
        if (!closed.compareAndSet(false, true)) return;
        try {
          output.close();
        } finally {
          endHandle();
        }
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
  public void renameReplace(String sourceAbsolutePath, String targetAbsolutePath)
      throws IOException {
    if (!(fileSystem instanceof org.apache.hadoop.hdfs.DistributedFileSystem dfs))
      throw new IOException("Atomic replacement requires HDFS DistributedFileSystem");
    dfs.rename(
        path(sourceAbsolutePath),
        path(targetAbsolutePath),
        org.apache.hadoop.fs.Options.Rename.OVERWRITE);
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
    if (!closeFileSystem) return;
    synchronized (this) {
      closeRequested = true;
      closeIfIdle();
    }
  }

  private synchronized void beginHandle() throws IOException {
    if (closeRequested) throw new IOException("HDFS client pool has been retired");
    openHandles++;
  }

  private synchronized void endHandle() throws IOException {
    openHandles--;
    closeIfIdle();
  }

  private void closeIfIdle() throws IOException {
    if (closeRequested && openHandles == 0 && !fileSystemClosed) {
      fileSystemClosed = true;
      fileSystem.close();
    }
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
