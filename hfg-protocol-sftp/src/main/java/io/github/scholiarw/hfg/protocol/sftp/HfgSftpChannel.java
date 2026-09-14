package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.transfer.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

final class HfgSftpChannel implements SeekableByteChannel {
  private final TransferService transfers;
  private final TransferContext context;
  private final String path;
  private final boolean readable;
  private final boolean writable;
  private final boolean overwrite;
  private TransferService.Download download;
  private TransferService.Upload upload;
  private long position;
  private boolean open = true;

  HfgSftpChannel(
      TransferService transfers,
      TransferContext context,
      String path,
      boolean readable,
      boolean writable,
      boolean overwrite,
      long initialPosition) {
    this.transfers = transfers;
    this.context = context;
    this.path = path;
    this.readable = readable;
    this.writable = writable;
    this.overwrite = overwrite;
    this.position = initialPosition;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    ensureOpen();
    if (!readable) throw new IOException("Channel is not readable");
    if (download == null) download = transfers.openDownload(context, path, position);
    else if (download.position() != position) download.seek(position);
    int n = download.read(dst);
    if (n > 0) position += n;
    return n;
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    ensureOpen();
    if (!writable) throw new IOException("Channel is not writable");
    if (upload == null)
      upload =
          transfers.openUpload(
              context,
              path,
              HfgSftpFileSystemAccessor.transferId(context.user().id(), path),
              position,
              overwrite);
    if (upload.position() != position)
      throw new IOException("HFG supports sequential writes and EOF resume only");
    int n = upload.write(src);
    position += n;
    return n;
  }

  @Override
  public long position() throws IOException {
    ensureOpen();
    return position;
  }

  @Override
  public SeekableByteChannel position(long newPosition) throws IOException {
    ensureOpen();
    if (newPosition < 0) throw new IllegalArgumentException("negative position");
    if (upload != null && upload.position() != newPosition)
      throw new IOException("Random upload offsets are unsupported");
    position = newPosition;
    return this;
  }

  @Override
  public long size() throws IOException {
    ensureOpen();
    try {
      return transfers.stat(context, path).length();
    } catch (Exception e) {
      return upload == null ? 0 : upload.position();
    }
  }

  @Override
  public SeekableByteChannel truncate(long size) throws IOException {
    ensureOpen();
    if (!writable) throw new IOException("Channel is not writable");
    if (size != 0) throw new IOException("Only truncate-to-zero is supported");
    position = 0;
    return this;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() throws IOException {
    if (!open) return;
    open = false;
    IOException failure = null;
    if (download != null)
      try {
        download.close();
      } catch (IOException e) {
        failure = e;
      }
    if (upload != null)
      try {
        upload.commit();
      } catch (IOException e) {
        failure = e;
      } finally {
        try {
          upload.close();
        } catch (IOException e) {
          if (failure == null) failure = e;
          else failure.addSuppressed(e);
        }
      }
    if (failure != null) throw failure;
  }

  private void ensureOpen() throws IOException {
    if (!open) throw new IOException("Channel closed");
  }
}
