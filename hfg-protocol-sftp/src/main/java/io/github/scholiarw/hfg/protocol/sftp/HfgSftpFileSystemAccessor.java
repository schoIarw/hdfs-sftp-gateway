package io.github.scholiarw.hfg.protocol.sftp;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.storage.StorageEntry;
import io.github.scholiarw.hfg.transfer.*;
import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.Principal;
import java.util.*;
import org.apache.sshd.sftp.server.*;

public final class HfgSftpFileSystemAccessor implements SftpFileSystemAccessor {
  private final UserSnapshotProvider users;
  private final TransferService transfers;
  private final String gatewayId;

  public HfgSftpFileSystemAccessor(
      UserSnapshotProvider users, TransferService transfers, String gatewayId) {
    this.users = users;
    this.transfers = transfers;
    this.gatewayId = gatewayId;
  }

  @Override
  public Path resolveLocalFilePath(SftpSubsystemProxy subsystem, Path rootDir, String remotePath)
      throws IOException {
    String raw = remotePath == null || remotePath.isBlank() ? "/" : remotePath.replace('\\', '/');
    var stack = new ArrayDeque<String>();
    for (String part : raw.split("/+")) {
      if (part.isBlank() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (stack.isEmpty()) throw new AccessDeniedException(remotePath);
        stack.removeLast();
      } else stack.addLast(part);
    }
    Path normalized = Path.of("/" + String.join("/", stack));
    if (!normalized.startsWith("/")) throw new AccessDeniedException(remotePath);
    return normalized;
  }

  @Override
  public SeekableByteChannel openFile(
      SftpSubsystemProxy subsystem,
      FileHandle handle,
      Path file,
      String handleId,
      Set<? extends OpenOption> options,
      FileAttribute<?>... attrs)
      throws IOException {
    boolean read = options.contains(StandardOpenOption.READ);
    boolean write =
        options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND);
    boolean overwrite =
        options.contains(StandardOpenOption.TRUNCATE_EXISTING)
            || options.contains(StandardOpenOption.CREATE);
    long initial =
        options.contains(StandardOpenOption.APPEND) ? size(context(subsystem), virtual(file)) : 0;
    return new HfgSftpChannel(
        transfers, context(subsystem), virtual(file), read, write, overwrite, initial);
  }

  @Override
  public void syncFileData(
      SftpSubsystemProxy subsystem,
      FileHandle fileHandle,
      Path file,
      String handle,
      Channel channel)
      throws IOException {
    // HDFS data is committed when the channel closes; flushing the staged upload is enough here.
    if (channel instanceof HfgSftpChannel hfgChannel) hfgChannel.flush();
  }

  @Override
  public DirectoryStream<Path> openDirectory(
      SftpSubsystemProxy subsystem,
      DirectoryHandle handle,
      Path dir,
      String handleId,
      LinkOption... options)
      throws IOException {
    List<Path> entries =
        transfers.list(context(subsystem), virtual(dir), null, 10_000).stream()
            .map(e -> Path.of(virtual(dir)).resolve(e.name()))
            .toList();
    return new DirectoryStream<>() {
      public Iterator<Path> iterator() {
        return entries.iterator();
      }

      public void close() {}
    };
  }

  @Override
  public Map<String, ?> readFileAttributes(
      SftpSubsystemProxy subsystem, Path file, String view, LinkOption... options)
      throws IOException {
    StorageEntry e = transfers.stat(context(subsystem), virtual(file));
    Set<PosixFilePermission> perms =
        e.directory()
            ? PosixFilePermissions.fromString("rwxr-x---")
            : PosixFilePermissions.fromString("rw-r-----");
    Map<String, Object> result = new TreeMap<>();
    result.put("size", e.length());
    result.put("isDirectory", e.directory());
    result.put("isRegularFile", !e.directory());
    result.put("isSymbolicLink", false);
    result.put("lastModifiedTime", FileTime.from(e.modifiedAt()));
    result.put("lastAccessTime", FileTime.from(e.modifiedAt()));
    result.put("creationTime", FileTime.from(e.modifiedAt()));
    result.put("permissions", perms);
    return result;
  }

  @Override
  public void createDirectory(SftpSubsystemProxy subsystem, Path path) throws IOException {
    transfers.mkdirs(context(subsystem), virtual(path));
  }

  @Override
  public void removeFile(SftpSubsystemProxy subsystem, Path path, boolean directory)
      throws IOException {
    transfers.delete(context(subsystem), virtual(path), false);
  }

  @Override
  public void renameFile(
      SftpSubsystemProxy subsystem, Path oldPath, Path newPath, Collection<CopyOption> options)
      throws IOException {
    transfers.rename(
        context(subsystem),
        virtual(oldPath),
        virtual(newPath),
        options.contains(StandardCopyOption.REPLACE_EXISTING));
  }

  @Override
  public void createLink(SftpSubsystemProxy subsystem, Path link, Path existing, boolean symbolic)
      throws IOException {
    throw new UnsupportedOperationException("Links are disabled");
  }

  @Override
  public String resolveLinkTarget(SftpSubsystemProxy subsystem, Path link) throws IOException {
    throw new UnsupportedOperationException("Links are disabled");
  }

  @Override
  public void setFilePermissions(
      SftpSubsystemProxy subsystem,
      Path file,
      Set<PosixFilePermission> perms,
      LinkOption... options) {
    throw new UnsupportedOperationException("Changing HDFS permissions through SFTP is disabled");
  }

  @Override
  public void setFileOwner(
      SftpSubsystemProxy subsystem, Path file, Principal value, LinkOption... options) {
    throw new UnsupportedOperationException("Changing HDFS ownership through SFTP is disabled");
  }

  @Override
  public void setGroupOwner(
      SftpSubsystemProxy subsystem, Path file, Principal value, LinkOption... options) {
    throw new UnsupportedOperationException(
        "Changing HDFS group ownership through SFTP is disabled");
  }

  @Override
  public void setFileAccessControl(
      SftpSubsystemProxy subsystem, Path file, List<AclEntry> acl, LinkOption... options) {
    throw new UnsupportedOperationException("Changing HDFS ACLs through SFTP is disabled");
  }

  @Override
  public UserPrincipal resolveFileOwner(
      SftpSubsystemProxy subsystem, Path file, UserPrincipal name) {
    throw new UnsupportedOperationException("Changing HDFS ownership through SFTP is disabled");
  }

  @Override
  public GroupPrincipal resolveGroupOwner(
      SftpSubsystemProxy subsystem, Path file, GroupPrincipal name) {
    throw new UnsupportedOperationException(
        "Changing HDFS group ownership through SFTP is disabled");
  }

  @Override
  public void setFileAttribute(
      SftpSubsystemProxy subsystem,
      Path file,
      String view,
      String attribute,
      Object value,
      LinkOption... options)
      throws IOException {
    throw new UnsupportedOperationException(
        "Changing HDFS file attributes through SFTP is disabled");
  }

  private TransferContext context(SftpSubsystemProxy subsystem) throws IOException {
    String username = subsystem.getSession().getUsername();
    UserSnapshot user =
        users.findByUsername(username).orElseThrow(() -> new AccessDeniedException(username));
    return new TransferContext(
        user,
        Protocol.SFTP,
        "/",
        gatewayId,
        String.valueOf(subsystem.getSession().getRemoteAddress()),
        UUID.randomUUID().toString());
  }

  private long size(TransferContext context, String path) {
    try {
      return transfers.stat(context, path).length();
    } catch (Exception e) {
      return 0;
    }
  }

  private static String virtual(Path path) {
    String p = path.normalize().toString().replace('\\', '/');
    return p.startsWith("/") ? p : "/" + p;
  }

  static UUID transferId(UUID userId, String path) {
    return UUID.nameUUIDFromBytes((userId + ":" + path).getBytes(StandardCharsets.UTF_8));
  }
}
