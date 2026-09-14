package io.github.scholiarw.hfg.policy;

import io.github.scholiarw.hfg.contract.DirectoryGrant;
import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;

public final class PathResolver {
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
    return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
  }

  public ResolvedPath resolve(
      List<DirectoryGrant> grants, String workingDirectory, String requestedPath) {
    String normalized = normalizeVirtualPath(workingDirectory, requestedPath);
    DirectoryGrant grant =
        grants.stream()
            .filter(g -> contains(g.virtualPath(), normalized))
            .max(Comparator.comparingInt(g -> g.virtualPath().length()))
            .orElseThrow(
                () -> new HfgException(HfgErrorCode.PERMISSION_DENIED, "Directory is not visible"));
    String suffix =
        "/".equals(grant.virtualPath())
            ? normalized
            : normalized.substring(grant.virtualPath().length());
    String hdfs = stripTrailingSlash(grant.hdfsPath()) + suffix;
    return new ResolvedPath(normalized, hdfs.isEmpty() ? "/" : hdfs, grant);
  }

  private static boolean contains(String root, String path) {
    return "/".equals(root) || path.equals(root) || path.startsWith(stripTrailingSlash(root) + "/");
  }

  private static String stripTrailingSlash(String value) {
    return value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  public record ResolvedPath(String virtualPath, String storagePath, DirectoryGrant grant) {}
}
