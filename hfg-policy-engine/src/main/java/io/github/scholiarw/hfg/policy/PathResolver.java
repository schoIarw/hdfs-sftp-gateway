package io.github.scholiarw.hfg.policy;

import io.github.scholiarw.hfg.contract.DirectoryGrant;
import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class PathResolver {
  /** 虚拟目录在列表中的标注后缀：挂在当前目录下的其它虚拟映射以“名字 (v)”出现，便于和真实 HDFS 目录区分。 客户端把该名字原样回传时会被自动还原，不影响进入目录、上传和下载。 */
  public static final String VIRTUAL_MARKER = " (v)";

  public String normalizeVirtualPath(String workingDirectory, String requestedPath) {
    String raw =
        requestedPath == null || requestedPath.isBlank()
            ? workingDirectory
            : requestedPath.startsWith("/")
                ? requestedPath
                : workingDirectory + "/" + requestedPath;
    if (raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) {
      throw new HfgException(HfgErrorCode.INVALID_PATH, "Invalid path character");
    }
    var parts = new ArrayDeque<String>();
    for (String part : raw.split("/+")) {
      if (part.isEmpty() || ".".equals(part)) continue;
      if ("..".equals(part)) {
        if (parts.isEmpty())
          throw new HfgException(HfgErrorCode.INVALID_PATH, "Path escapes virtual root");
        parts.removeLast();
      } else {
        parts.addLast(part);
      }
    }
    return stripVirtualMarker(parts.isEmpty() ? "/" : "/" + String.join("/", parts));
  }

  /** 去掉客户端回传的列表标注后缀，例如 “/c (v)/a.txt” → “/c/a.txt”。 */
  public static String stripVirtualMarker(String path) {
    if (path == null || path.indexOf(VIRTUAL_MARKER) < 0) return path;
    var cleaned = new ArrayDeque<String>();
    for (String part : path.split("/")) {
      if (part.isEmpty()) continue;
      cleaned.addLast(
          part.endsWith(VIRTUAL_MARKER)
              ? part.substring(0, part.length() - VIRTUAL_MARKER.length())
              : part);
    }
    return cleaned.isEmpty() ? "/" : "/" + String.join("/", cleaned);
  }

  public ResolvedPath resolve(
      List<DirectoryGrant> grants, String workingDirectory, String requestedPath) {
    return resolve(grants, workingDirectory, requestedPath, candidate -> false);
  }

  /**
   * 解析虚拟路径。
   *
   * <p>{@code storageExists} 用来判断某个真实路径是否存在，从而落实“真实目录优先”：如果某个更深层的虚拟 挂载点按上层映射算出来的真实路径已经存在（例如根映射 / →
   * /h01，同时存在虚拟目录 /c，而 HDFS 里已经有 /h01/c），该挂载点即失效并被遮蔽，路径继续按上层映射解析，也就是上传下载都落在真实目录上。
   */
  public ResolvedPath resolve(
      List<DirectoryGrant> grants,
      String workingDirectory,
      String requestedPath,
      Predicate<String> storageExists) {
    String normalized = normalizeVirtualPath(workingDirectory, requestedPath);
    List<DirectoryGrant> chain =
        grants.stream()
            .filter(g -> contains(g.virtualPath(), normalized))
            .sorted(Comparator.comparingInt(g -> g.virtualPath().length()))
            .toList();
    if (chain.isEmpty())
      throw new HfgException(HfgErrorCode.PERMISSION_DENIED, "Directory is not visible");
    DirectoryGrant grant = chain.get(0);
    for (DirectoryGrant deeper : chain.subList(1, chain.size())) {
      if (storageExists.test(mountStoragePath(grant, deeper))) continue;
      grant = deeper;
    }
    String suffix =
        "/".equals(grant.virtualPath())
            ? normalized
            : normalized.substring(grant.virtualPath().length());
    String hdfs = stripTrailingSlash(grant.hdfsPath()) + suffix;
    return new ResolvedPath(normalized, hdfs.isEmpty() ? "/" : hdfs, grant);
  }

  /** 更深层挂载点按较浅映射计算出来的真实路径，用于判断它是否已被真实目录遮蔽。 */
  private static String mountStoragePath(DirectoryGrant shallower, DirectoryGrant deeper) {
    String virtualRoot = stripTrailingSlash(shallower.virtualPath());
    String suffix =
        "/".equals(virtualRoot)
            ? deeper.virtualPath()
            : deeper.virtualPath().substring(virtualRoot.length());
    return stripTrailingSlash(shallower.hdfsPath()) + stripTrailingSlash(suffix);
  }

  private static boolean contains(String root, String path) {
    String normalized = stripTrailingSlash(root);
    return "/".equals(normalized) || path.equals(normalized) || path.startsWith(normalized + "/");
  }

  private static String stripTrailingSlash(String value) {
    return value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  public record ResolvedPath(String virtualPath, String storagePath, DirectoryGrant grant) {}
}
