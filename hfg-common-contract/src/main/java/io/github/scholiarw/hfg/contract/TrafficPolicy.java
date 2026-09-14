package io.github.scholiarw.hfg.contract;

import java.time.ZoneId;

public record TrafficPolicy(
    long uploadBytesPerSecond,
    long downloadBytesPerSecond,
    long uploadBurstBytes,
    long downloadBurstBytes,
    int maxConnections,
    int maxUploadTransfers,
    int maxDownloadTransfers,
    long periodUploadFiles,
    long periodDownloadFiles,
    long periodUploadBytes,
    long periodDownloadBytes,
    Period period,
    String timeZone) {

  public enum Period {
    DAY,
    WEEK,
    MONTH
  }

  public TrafficPolicy {
    if (uploadBytesPerSecond < 0
        || downloadBytesPerSecond < 0
        || uploadBurstBytes < 0
        || downloadBurstBytes < 0
        || maxConnections < 0
        || maxUploadTransfers < 0
        || maxDownloadTransfers < 0) {
      throw new IllegalArgumentException("traffic values must be non-negative");
    }
    period = period == null ? Period.DAY : period;
    timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone;
    ZoneId.of(timeZone);
  }

  public static TrafficPolicy unlimited() {
    return new TrafficPolicy(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Period.DAY, "UTC");
  }
}
