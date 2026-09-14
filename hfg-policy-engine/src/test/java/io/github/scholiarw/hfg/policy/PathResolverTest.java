package io.github.scholiarw.hfg.policy;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.AccessMode;
import io.github.scholiarw.hfg.contract.DirectoryGrant;
import io.github.scholiarw.hfg.contract.HfgException;
import java.util.List;
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
}
