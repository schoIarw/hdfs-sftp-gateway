package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.traffic.ConcurrencyGate;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpHelper;
import org.apache.sshd.sftp.server.DirectoryHandle;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemConfigurator;

/**
 * SFTP subsystem that resolves every command through the HFG storage layer only.
 *
 * <p>MINA validates resolved paths with {@code java.nio.file.Files} before delegating to the {@link
 * org.apache.sshd.sftp.server.SftpFileSystemAccessor}. HFG exposes virtual paths that map into
 * HDFS, so those checks either failed with {@code SSH_FX_NO_SUCH_FILE} (the file only exists in
 * HDFS) or inspected an unrelated same-named file on the gateway host. Every override below removes
 * such a check and lets the accessor (backed by {@code TransferService}) decide the outcome.
 */
final class HfgSftpSubsystem extends SftpSubsystem {
  private final ConcurrencyGate.Lease channelLease;

  HfgSftpSubsystem(
      ChannelSession channel,
      SftpSubsystemConfigurator configurator,
      ConcurrencyGate.Lease channelLease) {
    super(channel, configurator);
    this.channelLease = Objects.requireNonNull(channelLease);
  }

  @Override
  public void destroy(ChannelSession channel) {
    try {
      super.destroy(channel);
    } finally {
      channelLease.close();
    }
  }

  @Override
  protected NavigableMap<String, Object> resolveFileAttributes(
      Path path, int flags, boolean neverFollowSymLinks, LinkOption... options) throws IOException {
    // No checkSymlinkState()/Files.exists() probing of the gateway host file system.
    return getAttributes(path, flags, options);
  }

  @Override
  protected SimpleImmutableEntry<Path, Boolean> validateRealPath(
      int id, String path, Path f, LinkOption... options) {
    // REALPATH only canonicalizes the virtual path; existence is decided by the accessor.
    return new SimpleImmutableEntry<>(normalize(f), Boolean.TRUE);
  }

  @Override
  protected String doOpenDir(int id, String path, Path dir, LinkOption... options)
      throws IOException {
    try {
      synchronized (handles) {
        String handle = generateFileHandle(dir);
        handles.put(handle, new DirectoryHandle(this, dir, handle));
        return handle;
      }
    } catch (IOException e) {
      throw signalOpenFailure(id, path, dir, true, e);
    }
  }

  @Override
  protected void doReadDir(Buffer buffer, int id) throws IOException {
    String handle = buffer.getString(StandardCharsets.ISO_8859_1);
    Handle h = handles.get(handle);
    ServerSession session = getServerSession();
    boolean debugEnabled = log.isDebugEnabled();
    if (debugEnabled) {
      log.debug(
          "doReadDir({})[id={}] SSH_FXP_READDIR (handle={}[{}])",
          session,
          id,
          safeHandle(handle),
          h);
    }

    Buffer reply;
    try {
      DirectoryHandle dh = validateHandle(handle, h, DirectoryHandle.class);
      if (dh.isDone()) {
        sendStatus(prepareReply(buffer), id, SftpConstants.SSH_FX_EOF, "Directory reading is done");
        return;
      }

      // The listing was produced by the accessor when the directory handle was created, so the
      // gateway host file system is not consulted here.
      SftpEventListener listener = getSftpEventListenerProxy();
      listener.readingEntries(session, handle, dh);

      if (dh.isSendDot() || dh.isSendDotDot() || dh.hasNext()) {
        reply = prepareReply(buffer);
        reply.putByte((byte) SftpConstants.SSH_FXP_NAME);
        reply.putInt(id);

        int lenPos = reply.wpos();
        reply.putUInt(0L);

        int maxDataSize = SftpModuleProperties.MAX_READDIR_DATA_SIZE.getRequired(session);
        int count = doReadDir(id, handle, dh, reply, maxDataSize, false);
        BufferUtils.updateLengthPlaceholder(reply, lenPos, count);
        if ((!dh.isSendDot()) && (!dh.isSendDotDot()) && (!dh.hasNext())) {
          dh.markDone();
        }

        int sftpVersion = getVersion();
        Boolean indicator =
            SftpHelper.indicateEndOfNamesList(reply, sftpVersion, session, dh.isDone());
        if (debugEnabled) {
          log.debug(
              "doReadDir({})({})[{}] - sending {} entries - eol={} (SFTP version {})",
              session,
              safeHandle(handle),
              h,
              count,
              indicator,
              sftpVersion);
        }
      } else {
        dh.markDone();
        sendStatus(prepareReply(buffer), id, SftpConstants.SSH_FX_EOF, "Empty directory");
        return;
      }
      Objects.requireNonNull(reply, "No reply buffer created");
    } catch (IOException | RuntimeException | Error e) {
      sendStatus(prepareReply(buffer), id, e, SftpConstants.SSH_FXP_READDIR, safeHandle(handle));
      return;
    }

    send(reply);
  }

  @Override
  protected void doRemoveFile(int id, String path) throws IOException {
    // The accessor reports missing paths and read-only grants back as protocol errors.
    doRemove(id, resolveFile(path), false);
  }

  @Override
  protected void doRemoveDirectory(int id, String path) throws IOException {
    doRemove(id, resolveFile(path), true);
  }

  @Override
  protected void doMakeDirectory(int id, String path, Map<String, ?> attrs) throws IOException {
    Path resolved = resolveFile(path);
    ServerSession session = getServerSession();
    SftpEventListener listener = getSftpEventListenerProxy();
    listener.creating(session, resolved, attrs);
    try {
      getFileSystemAccessor().createDirectory(this, resolved);
    } catch (IOException | RuntimeException | Error e) {
      listener.created(session, resolved, attrs, e);
      throw e;
    }
    try {
      doSetAttributes(SftpConstants.SSH_FXP_MKDIR, "", resolved, attrs, true);
    } catch (IOException | RuntimeException attributeFailure) {
      // The directory exists; HDFS does not store POSIX attributes, so a rejected attribute
      // (OpenSSH always sends the requested mode) must not turn a successful mkdir into an error.
      log.warn(
          "Ignoring SFTP attributes of created directory {}: {}",
          resolved,
          attributeFailure.getMessage());
    }
    listener.created(session, resolved, attrs, null);
  }

  @Override
  protected void doCopyData(
      int id,
      String readHandle,
      long readOffset,
      long readLength,
      String writeHandle,
      long writeOffset)
      throws IOException {
    boolean inPlaceCopy = readHandle.equals(writeHandle);
    Handle rh = handles.get(readHandle);
    Handle wh = inPlaceCopy ? rh : handles.get(writeHandle);
    FileHandle srcHandle = validateHandle(readHandle, rh, FileHandle.class);
    Path srcPath = srcHandle.getFile();
    if ((srcHandle.getAccessMask() & SftpConstants.ACE4_READ_DATA)
        != SftpConstants.ACE4_READ_DATA) {
      throw new java.nio.file.AccessDeniedException(
          srcPath.toString(), srcPath.toString(), "Source file not opened for read");
    }
    if (readLength < 0L) throw new IllegalArgumentException("Invalid read length " + readLength);
    if (readOffset < 0L) throw new IllegalArgumentException("Invalid read offset " + readOffset);

    long totalSize = sizeOf(srcPath);
    long effectiveLength = readLength == 0L ? totalSize - readOffset : readLength;
    if (readOffset + effectiveLength > totalSize) effectiveLength = totalSize - readOffset;
    if (effectiveLength <= 0L) throw new IllegalArgumentException("Empty copy range");

    FileHandle dstHandle =
        inPlaceCopy ? srcHandle : validateHandle(writeHandle, wh, FileHandle.class);
    if ((dstHandle.getAccessMask() & SftpConstants.ACE4_WRITE_DATA)
        != SftpConstants.ACE4_WRITE_DATA) {
      throw new java.nio.file.AccessDeniedException(
          dstHandle.toString(), dstHandle.toString(), "Target handle not opened for write");
    }
    if (writeOffset < 0L) throw new IllegalArgumentException("Invalid write offset " + writeOffset);
    if (inPlaceCopy && readOffset + effectiveLength > writeOffset) {
      throw new IllegalArgumentException("Overlapping in-place copy is not supported");
    }

    byte[] copyBuf = new byte[Math.min(IoUtils.DEFAULT_COPY_SIZE, (int) effectiveLength)];
    while (effectiveLength > 0L) {
      int remainLength = Math.min(copyBuf.length, (int) effectiveLength);
      int readLen = srcHandle.read(copyBuf, 0, remainLength, readOffset);
      if (readLen < 0) throw new EOFException("Premature EOF while copying " + pathForLog(srcPath));
      dstHandle.write(copyBuf, 0, readLen, writeOffset);
      effectiveLength -= readLen;
      readOffset += readLen;
      writeOffset += readLen;
    }
  }

  @Override
  protected byte[] doMD5Hash(
      int id,
      String targetType,
      String target,
      long startOffset,
      long length,
      byte[] quickCheckHash) {
    throw new UnsupportedOperationException("HFG does not support the md5-hash extension");
  }

  @Override
  protected void doCheckFileHash(
      int id,
      String targetType,
      String target,
      Collection<String> algos,
      long startOffset,
      long length,
      int blockSize,
      Buffer buffer) {
    throw new UnsupportedOperationException("HFG does not support the check-file extension");
  }

  /** File size taken from the storage layer instead of the gateway host file system. */
  private long sizeOf(Path file) throws IOException {
    NavigableMap<String, Object> attrs = getAttributes(file, SftpConstants.SSH_FILEXFER_ATTR_SIZE);
    Object size = attrs.get(IoUtils.SIZE_VIEW_ATTR);
    return size instanceof Number number ? number.longValue() : 0L;
  }

  private static String safeHandle(String handle) {
    return handle == null ? "<none>" : handle;
  }

  private static String pathForLog(Path path) {
    return path == null ? "<unknown>" : path.toString();
  }
}
