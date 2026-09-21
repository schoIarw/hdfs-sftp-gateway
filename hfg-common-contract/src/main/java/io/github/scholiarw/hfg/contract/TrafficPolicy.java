package io.github.scholiarw.hfg.contract;

/** Per-user transfer rate and shared connection concurrency limits. Zero means unlimited. */
public record TrafficPolicy(
    long uploadBytesPerSecond, long downloadBytesPerSecond, int maxConnections) {

  public TrafficPolicy {
    if (uploadBytesPerSecond < 0 || downloadBytesPerSecond < 0 || maxConnections < 0)
      throw new IllegalArgumentException("traffic values must be non-negative");
  }

  public static TrafficPolicy unlimited() {
    return new TrafficPolicy(0, 0, 0);
  }
}
