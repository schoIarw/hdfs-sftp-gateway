package io.github.scholiarw.hfg.policy;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.AccessMode;
import io.github.scholiarw.hfg.contract.DirectoryGrant;
import io.github.scholiarw.hfg.contract.HfgException;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class PathResolverTest {
  private final PathResolver resolver = new PathResolver();

  @Test
  void resolvesLongestVisibleMapping() {
    var grants =
        List.of(
            new DirectoryGrant("/", "/tenants/u", AccessMode.READ_ONLY, -1, -1),
            new DirectoryGrant("/inbox", "/landing/u", AccessMode.READ_WRITE, -1, -1));
    assertEquals("/landing/u/a.txt", resolver.resolve(grants, "/", "/inbox/a.txt").storagePath());
  }

  @Test
  void rejectsRootEscapeAndPrefixCollision() {
    assertThrows(HfgException.class, () -> resolver.normalizeVirtualPath("/", "../secret"));
    var grants = List.of(new DirectoryGrant("/data", "/hdfs/data", AccessMode.READ_ONLY, -1, -1));
    assertThrows(HfgException.class, () -> resolver.resolve(grants, "/", "/database/x"));
  }

  @Test
  void restoresVirtualDirectoryMarkerFromClientRequests() {
    assertEquals("/c/a.txt", resolver.normalizeVirtualPath("/", "c (v)/a.txt"));
    assertEquals("/c", resolver.normalizeVirtualPath("/", "/c (v)"));
    assertEquals("/", resolver.normalizeVirtualPath("/", "/"));
  }

  @Test
  void realDirectoryShadowsVirtualMount() {
    var grants =
        List.of(
            new DirectoryGrant("/", "/h01", AccessMode.READ_WRITE, -1, -1),
            new DirectoryGrant("/c", "/hive/tablea", AccessMode.READ_ONLY, -1, -1));

    // /h01/c 不存在：/c 走虚拟映射
    assertEquals(
        "/hive/tablea/x.txt",
        resolver.resolve(grants, "/", "/c/x.txt", candidate -> false).storagePath());
    // /h01/c 存在：虚拟挂载失效，上传下载都落在真实目录上
    Predicate<String> realDirectory = "/h01/c"::equals;
    assertEquals(
        "/h01/c/x.txt", resolver.resolve(grants, "/", "/c/x.txt", realDirectory).storagePath());
    assertEquals("/h01/c", resolver.resolve(grants, "/", "/c (v)", realDirectory).storagePath());
    assertTrue(
        resolver.resolve(grants, "/", "/c", realDirectory).grant().virtualPath().equals("/"));
  }
}
