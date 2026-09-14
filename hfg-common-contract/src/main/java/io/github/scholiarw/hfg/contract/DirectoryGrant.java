package io.github.scholiarw.hfg.contract;

import java.util.Objects;

public record DirectoryGrant(
    String virtualPath,
    String hdfsPath,
    AccessMode accessMode,
    long namespaceQuota,
    long spaceQuotaBytes) {
  public DirectoryGrant {
    Objects.requireNonNull(virtualPath, "virtualPath");
    Objects.requireNonNull(hdfsPath, "hdfsPath");
    Objects.requireNonNull(accessMode, "accessMode");
    if (!virtualPath.startsWith("/") || !hdfsPath.startsWith("/")) {
      throw new IllegalArgumentException("virtualPath and hdfsPath must be absolute");
    }
    if (namespaceQuota < -1 || spaceQuotaBytes < -1) {
      throw new IllegalArgumentException("quota must be -1 (unlimited) or non-negative");
    }
  }
}
