package io.github.scholiarw.hfg.protocol.ftp;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.transfer.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.ftpserver.ftplet.FtpFile;

final class HfgFtpFile implements FtpFile {
  private final UserSnapshot user;
  private final TransferService transfers;
  private final String cwd;
  private final String path;
  private final String gatewayId;

  HfgFtpFile(
      UserSnapshot user, TransferService transfers, String cwd, String path, String gatewayId) {
    this.user = user;
    this.transfers = transfers;
    this.cwd = cwd;
    this.gatewayId = gatewayId;
    this.path = normalize(cwd, path);
  }

  private TransferContext context() {
    return new TransferContext(
        user, Protocol.FTP, cwd, gatewayId, null, UUID.randomUUID().toString());
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
    try {
      return transfers.stat(context(), path).directory();
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean isFile() {
    try {
      return !transfers.stat(context(), path).directory();
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean doesExist() {
    try {
      transfers.stat(context(), path);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public boolean isReadable() {
    try {
      transfers.stat(context(), path);
      return true;
    } catch (Exception e) {
      return false;
    }
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
    try {
      return transfers.stat(context(), path).owner();
    } catch (Exception e) {
      return user.username();
    }
  }

  @Override
  public String getGroupName() {
    try {
      return transfers.stat(context(), path).group();
    } catch (Exception e) {
      return user.department();
    }
  }

  @Override
  public int getLinkCount() {
    return 1;
  }

  @Override
  public long getLastModified() {
    try {
      return transfers.stat(context(), path).modifiedAt().toEpochMilli();
    } catch (Exception e) {
      return 0;
    }
  }

  @Override
  public boolean setLastModified(long time) {
    return false;
  }

  @Override
  public long getSize() {
    try {
      return transfers.stat(context(), path).length();
    } catch (Exception e) {
      return 0;
    }
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
          .map(e -> new HfgFtpFile(user, transfers, "/", join(path, e.name()), gatewayId))
          .toList();
    } catch (Exception e) {
      return null;
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
