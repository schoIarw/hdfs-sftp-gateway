package io.github.scholiarw.hfg.transfer;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.policy.PolicyEngine;
import io.github.scholiarw.hfg.storage.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransferService {
  private final StorageClientFactory storageFactory;
  private final PolicyEngine policy;
  private final TransferLimiter limiter;
  private final TransferEventSink events;

  public TransferService(
      StorageClientFactory storageFactory,
      PolicyEngine policy,
      TransferLimiter limiter,
      TransferEventSink events) {
    this.storageFactory = storageFactory;
    this.policy = policy;
    this.limiter = limiter;
    this.events = events;
  }

  public List<StorageEntry> list(TransferContext context, String path, String token, int pageSize)
      throws IOException {
    var resolved = policy.requireRead(context.user(), context.workingDirectory(), path);
    try (var storage = storage(context)) {
      return storage.list(resolved.storagePath(), token, pageSize);
    }
  }

  public StorageEntry stat(TransferContext context, String path) throws IOException {
    var resolved = policy.requireRead(context.user(), context.workingDirectory(), path);
    try (var storage = storage(context)) {
      return storage.stat(resolved.storagePath());
    }
  }

  public Download openDownload(TransferContext context, String path, long offset)
      throws IOException {
    if (offset < 0) throw new HfgException(HfgErrorCode.UNSUPPORTED_OFFSET, "Negative offset");
    var resolved = policy.requireRead(context.user(), context.workingDirectory(), path);
    StorageClient storage = storage(context);
    try {
      var handle = storage.openRead(resolved.storagePath(), offset);
      return new Download(
          UUID.randomUUID(),
          context,
          resolved.virtualPath(),
          storage,
          handle,
          limiter.open(context.user(), TransferDirection.DOWNLOAD),
          events);
    } catch (Exception e) {
      storage.close();
      throw e;
    }
  }

  public Upload openUpload(
      TransferContext context, String path, UUID transferId, long offset, boolean overwrite)
      throws IOException {
    if (offset < 0) throw new HfgException(HfgErrorCode.UNSUPPORTED_OFFSET, "Negative offset");
    var resolved = policy.requireWrite(context.user(), context.workingDirectory(), path);
    StorageClient storage = storage(context);
    String parent = parent(resolved.storagePath());
    String stagingDir = parent + "/.uploading";
    String stagingPath = stagingDir + "/" + transferId + ".part";
    try {
      storage.mkdirs(stagingDir);
      StorageWriteHandle handle;
      if (offset == 0) {
        handle = storage.create(stagingPath, true);
      } else {
        if (!storage.exists(stagingPath) || storage.stat(stagingPath).length() != offset) {
          throw new HfgException(
              HfgErrorCode.UNSUPPORTED_OFFSET, "Upload resume offset must equal staged EOF");
        }
        handle = storage.append(stagingPath);
      }
      return new Upload(
          transferId,
          context,
          resolved.virtualPath(),
          resolved.storagePath(),
          stagingPath,
          overwrite,
          storage,
          handle,
          limiter.open(context.user(), TransferDirection.UPLOAD),
          events);
    } catch (Exception e) {
      storage.close();
      throw e;
    }
  }

  public void mkdirs(TransferContext context, String path) throws IOException {
    var resolved = policy.requireWrite(context.user(), context.workingDirectory(), path);
    try (var storage = storage(context)) {
      storage.mkdirs(resolved.storagePath());
    }
  }

  public void delete(TransferContext context, String path, boolean recursive) throws IOException {
    var resolved = policy.requireWrite(context.user(), context.workingDirectory(), path);
    try (var storage = storage(context)) {
      storage.delete(resolved.storagePath(), recursive);
    }
  }

  public void rename(TransferContext context, String source, String target, boolean overwrite)
      throws IOException {
    var from = policy.requireWrite(context.user(), context.workingDirectory(), source);
    var to = policy.requireWrite(context.user(), context.workingDirectory(), target);
    try (var storage = storage(context)) {
      if (storage.exists(to.storagePath())) {
        if (!overwrite) throw new HfgException(HfgErrorCode.ALREADY_EXISTS, "Target exists");
        storage.delete(to.storagePath(), false);
      }
      if (!storage.rename(from.storagePath(), to.storagePath()))
        throw new IOException("Rename rejected by storage");
    }
  }

  private StorageClient storage(TransferContext context) throws IOException {
    return storageFactory.forEffectiveUser(context.user().hdfsEffectiveUser());
  }

  static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash <= 0 ? "/" : path.substring(0, slash);
  }

  static TransferEvent event(
      UUID id,
      TransferContext c,
      TransferDirection d,
      TransferStatus s,
      String path,
      long bytes,
      HfgErrorCode error) {
    return new TransferEvent(
        id,
        c.user().id(),
        c.protocol(),
        d,
        s,
        path,
        bytes,
        Instant.now(),
        c.gatewayId(),
        c.clientAddress(),
        error,
        c.correlationId());
  }

  public static final class Download implements AutoCloseable {
    private final UUID id;
    private final TransferContext context;
    private final String path;
    private final StorageClient storage;
    private final StorageReadHandle handle;
    private final TransferLimiter.Permit permit;
    private final TransferEventSink events;
    private long bytes;
    private boolean closed;

    Download(
        UUID id,
        TransferContext context,
        String path,
        StorageClient storage,
        StorageReadHandle handle,
        TransferLimiter.Permit permit,
        TransferEventSink events) {
      this.id = id;
      this.context = context;
      this.path = path;
      this.storage = storage;
      this.handle = handle;
      this.permit = permit;
      this.events = events;
      events.publish(
          event(id, context, TransferDirection.DOWNLOAD, TransferStatus.STARTED, path, 0, null));
    }

    public int read(ByteBuffer target) throws IOException {
      int read = handle.read(target);
      if (read > 0) {
        permit.acquire(read);
        bytes += read;
      }
      return read;
    }

    public void seek(long offset) throws IOException {
      handle.seek(offset);
    }

    public long position() throws IOException {
      return handle.position();
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      IOException failure = null;
      try {
        handle.close();
      } catch (IOException e) {
        failure = e;
      }
      try {
        storage.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
      permit.complete(failure == null);
      events.publish(
          event(
              id,
              context,
              TransferDirection.DOWNLOAD,
              failure == null ? TransferStatus.COMPLETED : TransferStatus.FAILED,
              path,
              bytes,
              failure == null ? null : HfgErrorCode.INTERNAL_ERROR));
      if (failure != null) throw failure;
    }
  }

  public static final class Upload implements AutoCloseable {
    private final UUID id;
    private final TransferContext context;
    private final String virtualPath;
    private final String target;
    private final String staging;
    private final boolean overwrite;
    private final StorageClient storage;
    private final StorageWriteHandle handle;
    private final TransferLimiter.Permit permit;
    private final TransferEventSink events;
    private final long initialPosition;
    private long bytes;
    private boolean completed;
    private boolean closed;

    Upload(
        UUID id,
        TransferContext context,
        String virtualPath,
        String target,
        String staging,
        boolean overwrite,
        StorageClient storage,
        StorageWriteHandle handle,
        TransferLimiter.Permit permit,
        TransferEventSink events) {
      this.id = id;
      this.context = context;
      this.virtualPath = virtualPath;
      this.target = target;
      this.staging = staging;
      this.overwrite = overwrite;
      this.storage = storage;
      this.handle = handle;
      this.permit = permit;
      this.events = events;
      this.initialPosition = handle.position();
      events.publish(
          event(
              id, context, TransferDirection.UPLOAD, TransferStatus.STARTED, virtualPath, 0, null));
    }

    public int write(ByteBuffer source) throws IOException {
      int n = source.remaining();
      permit.acquire(n);
      int wrote = handle.write(source);
      bytes += wrote;
      return wrote;
    }

    public void commit() throws IOException {
      if (completed) return;
      handle.flush();
      handle.close();
      if (storage.exists(target)) {
        if (!overwrite) throw new HfgException(HfgErrorCode.ALREADY_EXISTS, "Target exists");
        storage.delete(target, false);
      }
      if (!storage.rename(staging, target)) throw new IOException("Atomic commit rename failed");
      completed = true;
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      IOException failure = null;
      if (!completed)
        try {
          handle.close();
        } catch (IOException e) {
          failure = e;
        }
      try {
        storage.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
      permit.complete(completed && failure == null);
      events.publish(
          event(
              id,
              context,
              TransferDirection.UPLOAD,
              completed ? TransferStatus.COMPLETED : TransferStatus.ABORTED,
              virtualPath,
              bytes,
              failure == null ? null : HfgErrorCode.INTERNAL_ERROR));
      if (failure != null) throw failure;
    }

    public String stagingPath() {
      return staging;
    }

    public long bytes() {
      return bytes;
    }

    public long position() {
      return initialPosition + bytes;
    }
  }
}
