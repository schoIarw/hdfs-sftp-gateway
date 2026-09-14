package io.github.scholiarw.hfg.traffic;

import io.github.scholiarw.hfg.contract.TrafficPolicy;
import java.time.*;

public record UsageWindow(Instant startInclusive, Instant endExclusive) {
  public static UsageWindow containing(Instant instant, TrafficPolicy.Period period, ZoneId zone) {
    ZonedDateTime local = instant.atZone(zone);
    ZonedDateTime start =
        switch (period) {
          case DAY -> local.toLocalDate().atStartOfDay(zone);
          case WEEK ->
              local
                  .toLocalDate()
                  .minusDays(local.getDayOfWeek().getValue() - 1L)
                  .atStartOfDay(zone);
          case MONTH -> local.toLocalDate().withDayOfMonth(1).atStartOfDay(zone);
        };
    ZonedDateTime end =
        switch (period) {
          case DAY -> start.plusDays(1);
          case WEEK -> start.plusWeeks(1);
          case MONTH -> start.plusMonths(1);
        };
    return new UsageWindow(start.toInstant(), end.toInstant());
  }
}
