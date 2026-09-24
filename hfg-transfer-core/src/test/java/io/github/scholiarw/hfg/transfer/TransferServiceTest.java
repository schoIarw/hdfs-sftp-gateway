package io.github.scholiarw.hfg.transfer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.policy.*;
import io.github.scholiarw.hfg.storage.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

class TransferServiceTest {
  private MemoryStorage storage;
  private TransferService service;
  private TransferContext context;

  @BeforeEach
  void setUp() {
    storage = new MemoryStorage();
    service =
        new TransferService(
            u -> storage,
            new PolicyEngine(new PathResolver()),
            TransferLimiter.unlimited(),
            TransferEventSink.noop());
    var user =
        new UserSnapshot(
            UUID.randomUUID(),
            "user_01",
            "hash",
            Set.of(),
            "d",
            "b",
            "g",
            AccountStatus.ENABLED,
            null,
            List.of(new DirectoryGrant("/", "/tenant/user_01", AccessMode.READ_WRITE, -1, -1)),
            TrafficPolicy.unlimited());
    context = new TransferContext(user, Protocol.SFTP, "/", "gw", "client", "corr");
  }

  @Test
  void targetIsInvisibleUntilAtomicCommit() throws Exception {
    UUID id = UUID.randomUUID();
    try (var upload = service.openUpload(context, "/file.txt", id, 0, false)) {
      upload.write(ByteBuffer.wrap("hello".getBytes()));
      assertFalse(storage.exists("/tenant/user_01/file.txt"));
      assertTrue(storage.exists(upload.stagingPath()));
      upload.commit();
    }
    assertArrayEquals("hello".getBytes(), storage.files.get("/tenant/user_01/file.txt"));
  }

  @Test
  void listsVirtualMountsAndLetsRealDirectoriesWin() throws Exception {
    storage.dirs.add("/tenant/user_01/a");
    storage.dirs.add("/tenant/user_01/b");
    storage.dirs.add("/landing/tablea");
    var user =
        new UserSnapshot(
            UUID.randomUUID(),
            "user_01",
            "hash",
            Set.of(),
            "d",
            "b",
            "g",
            AccountStatus.ENABLED,
            null,
            List.of(
                new DirectoryGrant("/", "/tenant/user_01", AccessMode.READ_WRITE, -1, -1),
                new DirectoryGrant("/c", "/landing/tablea", AccessMode.READ_ONLY, -1, -1)),
            TrafficPolicy.unlimited());
    var session = new TransferContext(user, Protocol.FTP, "/", "gw", null, "c");

    // 真实目录 a、b 与虚拟挂载点 c 一起出现在根目录，虚拟目录带 (v) 标注
    assertEquals(
        List.of("a", "b", "c (v)"),
        service.list(session, "/", null, 1000).stream().map(StorageEntry::name).toList());
    // 客户端把带标注的名字回传时仍能进入虚拟目录
    assertEquals("/landing/tablea", service.stat(session, "/c (v)").path());
    assertEquals("/landing/tablea", service.stat(session, "/c").path());

    // 真实目录出现同名条目后，虚拟挂载点失效：列表只剩真实目录，路径也落到真实目录上
    storage.dirs.add("/tenant/user_01/c");
    assertEquals(
        List.of("a", "b", "c"),
        service.list(session, "/", null, 1000).stream().map(StorageEntry::name).toList());
    assertEquals("/tenant/user_01/c", service.stat(session, "/c (v)").path());
    assertEquals("/tenant/user_01/c", service.stat(session, "/c").path());
  }

  @Test
  void resumesOnlyAtStagedEof() throws Exception {
    UUID id = UUID.randomUUID();
    try (var first = service.openUpload(context, "/resume.bin", id, 0, true)) {
      first.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    }
    assertThrows(HfgException.class, () -> service.openUpload(context, "/resume.bin", id, 1, true));
    try (var resumed = service.openUpload(context, "/resume.bin", id, 3, true)) {
      resumed.write(ByteBuffer.wrap(new byte[] {4, 5}));
      resumed.commit();
    }
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, storage.files.get("/tenant/user_01/resume.bin"));
  }

  @Test
  void resumedUploadReportsTheCompleteFileSize() throws Exception {
    List<TransferEvent> events = new ArrayList<>();
    var reportingService =
        new TransferService(
            u -> storage,
            new PolicyEngine(new PathResolver()),
            TransferLimiter.unlimited(),
            events::add);
    UUID id = UUID.randomUUID();

    try (var first = reportingService.openUpload(context, "/resume.bin", id, 0, true)) {
      first.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    }
    try (var resumed = reportingService.openUpload(context, "/resume.bin", id, 3, true)) {
      resumed.write(ByteBuffer.wrap(new byte[] {4, 5}));
      resumed.commit();
    }

    TransferEvent completed =
        events.stream()
            .filter(event -> event.status() == TransferStatus.COMPLETED)
            .findFirst()
            .orElseThrow();
    assertEquals(5, completed.bytes());
  }

  @Test
  void capacityRejectionDoesNotOpenAnHdfsStreamOrCreateStaging() throws Exception {
    var bounded =
        new TransferService(
            u -> storage,
            new PolicyEngine(new PathResolver()),
            new NodeTransferLimiter(TransferLimiter.unlimited(), 1, 1, 1, 0, Duration.ZERO),
            TransferEventSink.noop());
    storage.files.put("/tenant/user_01/file.bin", new byte[] {1});
    try (var first = bounded.openDownload(context, "/file.bin", 0)) {
      assertEquals(1, storage.readOpens);
      assertThrows(HfgException.class, () -> bounded.openDownload(context, "/file.bin", 0));
      assertEquals(1, storage.readOpens);
      assertThrows(
          HfgException.class,
          () -> bounded.openUpload(context, "/other.bin", UUID.randomUUID(), 0, true));
      assertEquals(0, storage.writeCreates);
    }
  }

  @Test
  void secondUploadCannotTruncateAnInFlightStagingFile() throws Exception {
    UUID id = UUID.randomUUID();
    try (var first = service.openUpload(context, "/file.bin", id, 0, true)) {
      first.write(ByteBuffer.wrap(new byte[] {1, 2}));
      assertThrows(
          IOException.class, () -> service.openUpload(context, "/file.bin", id, 0, true));
      assertArrayEquals(new byte[] {1, 2}, storage.files.get(first.stagingPath()));
    }
  }

  @Test
  void failedAtomicReplacementKeepsTheOriginalTarget() throws Exception {
    String target = "/tenant/user_01/file.bin";
    storage.files.put(target, new byte[] {1, 2, 3});
    storage.failReplace = true;
    try (var upload = service.openUpload(context, "/file.bin", UUID.randomUUID(), 0, true)) {
      upload.write(ByteBuffer.wrap(new byte[] {4, 5}));
      assertThrows(IOException.class, upload::commit);
    }
    assertArrayEquals(new byte[] {1, 2, 3}, storage.files.get(target));
  }

  @Test
  void readOnlyGrantRejectsWrite() {
    var user =
        new UserSnapshot(
            UUID.randomUUID(),
            "reader",
            "hash",
            Set.of(),
            "d",
            "b",
            "g",
            AccountStatus.ENABLED,
            null,
            List.of(new DirectoryGrant("/", "/readonly", AccessMode.READ_ONLY, -1, -1)),
            TrafficPolicy.unlimited());
    var c = new TransferContext(user, Protocol.FTP, "/", "gw", null, "c");
    assertThrows(
        HfgException.class, () -> service.openUpload(c, "/x", UUID.randomUUID(), 0, false));
  }

  static final class MemoryStorage implements StorageClient {
    final Map<String, byte[]> files = new HashMap<>();
    final Set<String> dirs = new HashSet<>(Set.of("/", "/tenant", "/tenant/user_01"));
    int readOpens;
    int writeCreates;
    boolean failReplace;

    public StorageEntry stat(String p) throws IOException {
      if (dirs.contains(p))
        return new StorageEntry(p, name(p), true, 0, Instant.EPOCH, "u", "g", "rwx");
      byte[] b = files.get(p);
      if (b == null) throw new FileNotFoundException(p);
      return new StorageEntry(p, name(p), false, b.length, Instant.EPOCH, "u", "g", "rw-");
    }

    public List<StorageEntry> list(String p, String token, int size) {
      String prefix = p.endsWith("/") ? p : p + "/";
      return java.util.stream.Stream.concat(
              dirs.stream()
                  .filter(
                      x ->
                          x.startsWith(prefix)
                              && !x.equals(p)
                              && !x.substring(prefix.length()).contains("/"))
                  .map(x -> entry(x, true, 0)),
              files.entrySet().stream()
                  .filter(
                      e ->
                          e.getKey().startsWith(prefix)
                              && !e.getKey().substring(prefix.length()).contains("/"))
                  .map(e -> entry(e.getKey(), false, e.getValue().length)))
          .sorted(Comparator.comparing(StorageEntry::name))
          .limit(size)
          .toList();
    }

    public StorageReadHandle openRead(String p, long offset) throws IOException {
      readOpens++;
      byte[] b = files.get(p);
      if (b == null) throw new FileNotFoundException(p);
      return new StorageReadHandle() {
        int pos = (int) offset;

        public int read(ByteBuffer t) {
          if (pos >= b.length) return -1;
          int n = Math.min(t.remaining(), b.length - pos);
          t.put(b, pos, n);
          pos += n;
          return n;
        }

        public void seek(long o) {
          pos = (int) o;
        }

        public long position() {
          return pos;
        }

        public void close() {}
      };
    }

    public StorageWriteHandle create(String p, boolean overwrite) throws IOException {
      writeCreates++;
      if (!overwrite && files.containsKey(p)) throw new IOException("exists");
      return writer(p, new byte[0]);
    }

    public StorageWriteHandle append(String p) throws IOException {
      byte[] b = files.get(p);
      if (b == null) throw new FileNotFoundException(p);
      return writer(p, b);
    }

    private StorageWriteHandle writer(String p, byte[] initial) {
      var out = new ByteArrayOutputStream();
      try {
        out.write(initial);
      } catch (IOException impossible) {
        throw new AssertionError(impossible);
      }
      files.put(p, initial);
      return new StorageWriteHandle() {
        public int write(ByteBuffer s) {
          int n = s.remaining();
          byte[] b = new byte[n];
          s.get(b);
          try {
            out.write(b);
          } catch (IOException impossible) {
            throw new AssertionError(impossible);
          }
          files.put(p, out.toByteArray());
          return n;
        }

        public void flush() {}

        public long position() {
          return out.size();
        }

        public void close() {
          files.put(p, out.toByteArray());
        }
      };
    }

    public boolean mkdirs(String p) {
      String current = "";
      for (String part : p.split("/")) {
        if (!part.isBlank()) {
          current += "/" + part;
          dirs.add(current);
        }
      }
      return true;
    }

    public boolean delete(String p, boolean recursive) {
      return files.remove(p) != null || dirs.remove(p);
    }

    public boolean rename(String a, String b) {
      byte[] v = files.remove(a);
      if (v == null) return false;
      files.put(b, v);
      return true;
    }

    public void renameReplace(String a, String b) throws IOException {
      if (failReplace) throw new IOException("simulated rename failure");
      byte[] value = files.remove(a);
      if (value == null) throw new FileNotFoundException(a);
      files.put(b, value);
    }

    public QuotaUsage quota(String p) {
      return new QuotaUsage(
          -1,
          files.size() + dirs.size(),
          -1,
          files.values().stream().mapToLong(x -> x.length).sum());
    }

    public void setQuota(String p, long n, long s) {}

    public boolean exists(String p) {
      return files.containsKey(p) || dirs.contains(p);
    }

    public void close() {}

    static StorageEntry entry(String p, boolean d, long l) {
      return new StorageEntry(p, name(p), d, l, Instant.EPOCH, "u", "g", d ? "rwx" : "rw-");
    }

    static String name(String p) {
      return "/".equals(p) ? "/" : p.substring(p.lastIndexOf('/') + 1);
    }
  }
}
