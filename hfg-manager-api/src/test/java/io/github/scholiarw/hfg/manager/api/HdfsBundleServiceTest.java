package io.github.scholiarw.hfg.manager.api;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HdfsBundleServiceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void extractsOnlyHadoopConfigurationAndKeytabFiles() throws Exception {
    Path zip =
        archive(
            "conf/core-site.xml",
            "<configuration/>",
            "auth.keytab",
            "keytab",
            "note.txt",
            "ignored");

    var files = HdfsBundleService.extract(zip, temporaryDirectory.resolve("out"));

    assertThat(files)
        .extracting(p -> p.getFileName().toString())
        .containsExactlyInAnyOrder("core-site.xml", "auth.keytab");
    assertThat(temporaryDirectory.resolve("out/note.txt")).doesNotExist();
  }

  @Test
  void rejectsZipSlipEntries() throws Exception {
    Path zip = archive("../escape.xml", "unsafe");

    assertThatThrownBy(() -> HdfsBundleService.extract(zip, temporaryDirectory.resolve("out")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsafe ZIP entry");
    assertThat(temporaryDirectory.resolve("escape.xml")).doesNotExist();
  }

  @Test
  void allowsDeletingAnUnreferencedConnection() {
    assertThatCode(() -> HdfsBundleService.requireUnreferenced("hadoop01", List.of(), List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesToDeleteAConnectionThatIsStillReferenced() {
    assertThatThrownBy(
            () ->
                HdfsBundleService.requireUnreferenced(
                    "hadoop01", List.of("hadoop01"), List.of("landing")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hadoop01")
        .hasMessageContaining("服务组")
        .hasMessageContaining("目录映射");
  }

  @Test
  void rejectsUnsafeConnectionIdentifiers() {
    assertThatThrownBy(() -> HdfsBundleService.requireValidId("../escape"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HDFS 连接标识");
    assertThatThrownBy(() -> HdfsBundleService.requireValidId("a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> HdfsBundleService.requireValidId("hadoop01")).doesNotThrowAnyException();
  }

  @Test
  void reportsWhetherTheStoredBundleIsStillOnDisk() throws Exception {
    Path bundle = temporaryDirectory.resolve("bundle.zip");
    Files.writeString(bundle, "zip");
    assertThat(HdfsBundleService.bundlePresent(bundle.toString())).isTrue();
    assertThat(HdfsBundleService.bundlePresent(bundle + ".missing")).isFalse();
    assertThat(HdfsBundleService.bundlePresent(null)).isFalse();
  }

  private Path archive(String... nameAndContent) throws IOException {
    Path archive = temporaryDirectory.resolve("bundle-" + System.nanoTime() + ".zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (int index = 0; index < nameAndContent.length; index += 2) {
        output.putNextEntry(new ZipEntry(nameAndContent[index]));
        output.write(nameAndContent[index + 1].getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return archive;
  }
}
