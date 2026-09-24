package io.github.scholiarw.hfg.protocol.ftp;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.storage.StorageEntry;
import io.github.scholiarw.hfg.transfer.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.ftpserver.ftplet.FtpFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HfgFtpFile implements FtpFile {
  private static final Logger log = LoggerFactory.getLogger(HfgFtpFile.class);

  private final UserSnapshot user;
  private final TransferService transfers;
  private final String cwd;
  private final String path;
  private final String gatewayId;

  /**
   * Status already fetched by the enclosing directory listing. A LIST reply reads roughly seven
   * attributes per entry; resolving each one with a fresh HDFS getFileStatus RPC amplifies one
   * listing into thousands of NameNode calls. Entries produced by {@link #listFiles()} carry their
   * status here so attribute reads never touch the storage layer again.
   */
  private final StorageEntry known;

  /** Lazily fetched status for path-created instances; fetched at most once per instance. */
  private StorageEntry stat;

  private boolean statAttempted;

  HfgFtpFile(
      UserSnapshot user, TransferService transfers, String cwd, String path, String gatewayId) {
    this(user, transfers, cwd, path, gatewayId, null);
  }

  HfgFtpFile(
      UserSnapshot user,
      TransferService transfers,
      String cwd,
      String path,
      String gatewayId,
      StorageEntry known) {
    this.user = user;
    this.transfers = transfers;
    this.cwd = cwd;
    this.gatewayId = gatewayId;
    this.path = normalize(cwd, path);
    this.known = known;
  }

  private TransferContext context() {
    return new TransferContext(
        user, Protocol.FTP, cwd, gatewayId, null, UUID.randomUUID().toString());
  }

  /**
   * Returns the entry status with at most one storage RPC for the whole lifetime of this instance:
   * either the listing already supplied it, or the first attribute read fetches it once and every
   * later attribute read reuses the same result. Repeated RPCs within one LIST would otherwise hit
   * the NameNode once per attribute.
   */
  private StorageEntry stat() {
    if (known != null) return known;
    if (stat == null && !statAttempted) {
      statAttempted = true;
      try {
        stat = transfers.stat(context(), path);
      } catch (Exception e) {
        stat = null;
      }
    }
    return stat;
  }

  @Override
  public String getAbsolutePath() {
    return path;
  }

  @Override
  public String getName() {
    return "/".equals(path) ? "/" : path.substring(path.lastIndexOf('/') + 1);
  }

  @Override
  public boolean isHidden() {
    return getName().startsWith(".");
  }

  @Override
  public boolean isDirectory() {
    StorageEntry entry = stat();
    return entry != null && entry.directory();
  }

  @Override
  public boolean isFile() {
    StorageEntry entry = stat();
    return entry != null && !entry.directory();
  }

  @Override
  public boolean doesExist() {
    return stat() != null;
  }

  @Override
  public boolean isReadable() {
    return stat() != null;
  }

  @Override
  public boolean isWritable() {
    return user.directories().stream()
        .anyMatch(g -> g.accessMode().canWrite() && contains(g.virtualPath(), path));
  }

  @Override
  public boolean isRemovable() {
    return isWritable() && !"/".equals(path);
  }

  @Override
  public String getOwnerName() {
    StorageEntry entry = stat();
    return entry == null ? user.username() : entry.owner();
  }

  @Override
  public String getGroupName() {
    StorageEntry entry = stat();
    return entry == null ? user.department() : entry.group();
  }

  @Override
  public int getLinkCount() {
    return 1;
  }

  @Override
  public long getLastModified() {
    StorageEntry entry = stat();
    return entry == null ? 0 : entry.modifiedAt().toEpochMilli();
  }

  @Override
  public boolean setLastModified(long time) {
    return false;
  }

  @Override
  public long getSize() {
    StorageEntry entry = stat();
    return entry == null ? 0 : entry.length();
  }

  @Override
  public Object getPhysicalFile() {
    return path;
  }

  @Override
  public boolean mkdir() {
    try {
      transfers.mkdirs(context(), path);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean delete() {
    try {
      transfers.delete(context(), path, false);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean move(FtpFile destination) {
    try {
      transfers.rename(context(), path, destination.getAbsolutePath(), false);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public List<? extends FtpFile> listFiles() {
    try {
      return transfers.list(context(), path, null, 10_000).stream()
          .map(e -> new HfgFtpFile(user, transfers, "/", join(path, e.name()), gatewayId, e))
          .toList();
    } catch (IOException | RuntimeException e) {
      // Apache FtpServer's FtpFile.listFiles() declares no checked exception in 1.2.1, and a null
      // return makes the reply formatter fail with an internal error (500). Log the real cause -
      // e.g. a directory beyond the 10,000-entry scanning limit - and return an empty listing so
      // the control channel stays usable instead of dropping the session.
      log.error("Failed to list directory {}: {}", path, e.getMessage(), e);
      return List.of();
    }
  }

  @Override
  public OutputStream createOutputStream(long offset) throws IOException {
    UUID transferId =
        UUID.nameUUIDFromBytes((user.id() + ":" + path).getBytes(StandardCharsets.UTF_8));
    TransferService.Upload upload = transfers.openUpload(context(), path, transferId, offset, true);
    return new OutputStream() {
      private boolean closed;

      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        upload.write(ByteBuffer.wrap(b, off, len));
      }

      @Override
      public void close() throws IOException {
        if (!closed) {
          closed = true;
          try {
            upload.commit();
          } finally {
            upload.close();
          }
        }
      }
    };
  }

  @Override
  public InputStream createInputStream(long offset) throws IOException {
    TransferService.Download download = transfers.openDownload(context(), path, offset);
    return new InputStream() {
      @Override
      public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : Byte.toUnsignedInt(b[0]);
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        return download.read(ByteBuffer.wrap(b, off, len));
      }

      @Override
      public void close() throws IOException {
        download.close();
      }
    };
  }

  private static String join(String parent, String child) {
    return "/".equals(parent) ? "/" + child : parent + "/" + child;
  }

  private static boolean contains(String root, String child) {
    return "/".equals(root) || child.equals(root) || child.startsWith(root + "/");
  }

  private static String normalize(String cwd, String requested) {
    String raw =
        requested == null || requested.isBlank()
            ? cwd
            : requested.startsWith("/") ? requested : join(cwd, requested);
    var stack = new java.util.ArrayDeque<String>();
    for (String p : raw.split("/+")) {
      if (p.isBlank() || p.equals(".")) continue;
      if (p.equals("..")) {
        if (!stack.isEmpty()) stack.removeLast();
      } else stack.addLast(p);
    }
    return stack.isEmpty() ? "/" : "/" + String.join("/", stack);
  }
}
