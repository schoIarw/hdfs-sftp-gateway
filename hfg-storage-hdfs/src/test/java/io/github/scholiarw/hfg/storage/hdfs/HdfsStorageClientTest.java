package io.github.scholiarw.hfg.storage.hdfs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.junit.jupiter.api.Test;

class HdfsStorageClientTest {
  @Test
  void writesReadsAndListsUsingHadoopFileSystemContract() throws Exception {
    var conf = new Configuration(false);
    conf.set("fs.defaultFS", "file:///");
    var root = java.nio.file.Files.createTempDirectory("hfg-hdfs-adapter-");
    try (var storage = new HdfsStorageClient(FileSystem.newInstance(conf))) {
      String dir = root.toAbsolutePath().toString();
      storage.mkdirs(dir);
      try (var out = storage.create(dir + "/a.bin", false)) {
        out.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
      }
      ByteBuffer direct = ByteBuffer.allocateDirect(3);
      direct.put(new byte[] {4, 5, 6}).flip();
      try (var out = storage.create(dir + "/direct.bin", false)) {
        out.write(direct);
      }
      assertEquals(3, storage.stat(dir + "/a.bin").length());
      try (var in = storage.openRead(dir + "/a.bin", 1)) {
        var target = ByteBuffer.allocate(2);
        assertEquals(2, in.read(target));
        assertArrayEquals(new byte[] {2, 3}, target.array());
      }
      try (var in = storage.openRead(dir + "/direct.bin", 0)) {
        ByteBuffer target = ByteBuffer.allocateDirect(3);
        assertEquals(3, in.read(target));
        target.flip();
        assertEquals(4, target.get());
        assertEquals(5, target.get());
        assertEquals(6, target.get());
      }
      assertEquals("a.bin", storage.list(dir, null, 10).get(0).name());
      assertThrows(UnsupportedOperationException.class, () -> storage.setQuota(dir, 10, 1024));
    }
  }
}
