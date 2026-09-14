package io.github.scholiarw.hfg.storage;

public record QuotaUsage(
    long namespaceQuota, long namespaceConsumed, long spaceQuotaBytes, long spaceConsumedBytes) {
  public static final long UNLIMITED = -1L;
}
