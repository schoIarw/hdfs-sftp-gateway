package io.github.scholiarw.hfg.protocol.ftp;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.policy.PathResolver;
import io.github.scholiarw.hfg.policy.PolicyEngine;
import io.github.scholiarw.hfg.storage.*;
import io.github.scholiarw.hfg.transfer.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.apache.ftpserver.ftplet.FtpFile;
import org.junit.jupiter.api.Test;

/**
 * Guards the LIST hot path against NameNode RPC amplification: entries produced by {@code
 * listFiles()} must answer attribute reads from the status already fetched by the listing, and a
 * failing listing must return an empty list (never null, which crashes the reply formatter).
 */
class HfgFtpFileTest {
  private final CountingStorage storage = new CountingStorage();
  private final TransferService service =
      new TransferService(
          u -> storage,
          new PolicyEngine(new PathResolver()),
          TransferLimiter.unlimited(),
          TransferEventSink.noop());
  private final UserSnapshot user =
      new UserSnapshot(
          UUID.randomUUID(),
          "ftp-user",
          "hash",
          Set.of(),
          null,
          null,
          "g",
          AccountStatus.ENABLED,
          null,
          List.of(new DirectoryGrant("/", "/data", AccessMode.READ_WRITE, -1, -1)),
          TrafficPolicy.unlimited());

  @Test
  void listEntriesAnswerAttributesWithoutExtraStatRpc() throws Exception {
    storage.files.put("/data/dir/a.txt", new byte[] {1, 2, 3, 4, 5});
    storage.dirs.add("/data/dir");
    storage.dirs.add("/data/dir/sub");

    HfgFtpFile directory = new HfgFtpFile(user, service, "/", "/dir", "gw");
    List<? extends FtpFile> children = directory.listFiles();
    assertEquals(List.of("a.txt", "sub"), children.stream().map(FtpFile::getName).toList());

    int statsBefore = storage.statCalls;
    for (FtpFile child : children) {
      child.isDirectory();
      child.isFile();
      child.doesExist();
      child.isReadable();
      child.getOwnerName();
      child.getGroupName();
      child.getSize();
      child.getLastModified();
    }
    // The listing carried each entry's status; the eight attribute reads above must not trigger a
    // single additional getFileStatus RPC.
    assertEquals(statsBefore, storage.statCalls);
  }

  @Test
  void pathCreatedFileStatsAtMostOnceAcrossAllAttributeReads() {
    storage.files.put("/data/dir/a.txt", new byte[] {1, 2, 3, 4, 5});
    storage.dirs.add("/data/dir");

    HfgFtpFile file = new HfgFtpFile(user, service, "/", "/dir/a.txt", "gw");
    assertFalse(file.isDirectory());
    assertTrue(file.doesExist());
    assertEquals(5, file.getSize());
    assertEquals("u", file.getOwnerName());
    assertEquals("g", file.getGroupName());
    assertEquals(0, file.getLastModified());
    assertTrue(file.isReadable());
    assertTrue(file.isFile());

    assertEquals(1, storage.statCalls);
  }

  @Test
  void listFailureReturnsEmptyListInsteadOfNull() throws Exception {
    storage.listFailure =
        new IOException(
            "Directory contains more than 10000 entries; refine the directory layout before listing it through HFG");
    HfgFtpFile directory = new HfgFtpFile(user, service, "/", "/huge", "gw");

    // FtpFile.listFiles() in ftpserver 1.2.1 declares no checked exception and a null return makes
    // the reply formatter crash with an internal error; an empty list keeps the control channel up.
    List<? extends FtpFile> children = directory.listFiles();
    assertNotNull(children);
    assertTrue(children.isEmpty());
  }

  /** In-memory storage with a stat call counter and a switchable list failure. */
  static final class CountingStorage implements StorageClient {
    final Map<String, byte[]> files = new HashMap<>();
    final Set<String> dirs = new HashSet<>(Set.of("/", "/data", "/data/dir"));
    int statCalls;
    IOException listFailure;

    @Override
    public StorageEntry stat(String absolutePath) throws IOException {
      statCalls++;
      if (dirs.contains(absolutePath))
        return new StorageEntry(
            absolutePath, name(absolutePath), true, 0, Instant.EPOCH, "u", "g", "rwx");
      byte[] bytes = files.get(absolutePath);
      if (bytes == null) throw new FileNotFoundException(absolutePath);
      return new StorageEntry(
          absolutePath, name(absolutePath), false, bytes.length, Instant.EPOCH, "u", "g", "rw-");
    }

    @Override
    public List<StorageEntry> list(String absolutePath, String pageToken, int pageSize)
        throws IOException {
      if (listFailure != null) throw listFailure;
      String prefix = absolutePath.endsWith("/") ? absolutePath : absolutePath + "/";
      return java.util.stream.Stream.concat(
              dirs.stream()
                  .filter(
                      x ->
                          x.startsWith(prefix)
                              && !x.equals(absolutePath)
                              && !x.substring(prefix.length()).contains("/"))
                  .map(x -> new StorageEntry(x, name(x), true, 0, Instant.EPOCH, "u", "g", "rwx")),
              files.keySet().stream()
                  .filter(x -> x.startsWith(prefix) && !x.substring(prefix.length()).contains("/"))
                  .map(
                      x ->
                          new StorageEntry(
                              x,
                              name(x),
                              false,
                              files.get(x).length,
                              Instant.EPOCH,
                              "u",
                              "g",
                              "rw-")))
          .sorted(Comparator.comparing(StorageEntry::name))
          .limit(pageSize)
          .toList();
    }

    private static String name(String path) {
      return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public boolean exists(String absolutePath) {
      return dirs.contains(absolutePath) || files.containsKey(absolutePath);
    }

    @Override
    public StorageReadHandle openRead(String absolutePath, long offset) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StorageWriteHandle create(String absolutePath, boolean overwrite) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StorageWriteHandle append(String absolutePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean mkdirs(String absolutePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete(String absolutePath, boolean recursive) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean rename(String sourceAbsolutePath, String targetAbsolutePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuotaUsage quota(String absolutePath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setQuota(String absolutePath, long namespaceQuota, long spaceQuotaBytes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }
}