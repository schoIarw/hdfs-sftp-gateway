package io.github.scholiarw.hfg.traffic;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scholiarw.hfg.contract.TrafficPolicy;
import java.time.*;
import org.junit.jupiter.api.Test;

class UsageWindowTest {
  @Test
  void dayWindowHonorsTimeZoneAndDst() {
    ZoneId zone = ZoneId.of("America/New_York");
    var w =
        UsageWindow.containing(
            Instant.parse("2026-03-08T12:00:00Z"), TrafficPolicy.Period.DAY, zone);
    assertEquals(23, Duration.between(w.startInclusive(), w.endExclusive()).toHours());
  }

  @Test
  void weekStartsOnMonday() {
    var w =
        UsageWindow.containing(
            Instant.parse("2026-09-13T10:00:00Z"), TrafficPolicy.Period.WEEK, ZoneOffset.UTC);
    assertEquals(Instant.parse("2026-09-07T00:00:00Z"), w.startInclusive());
  }
}
