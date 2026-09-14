package io.github.scholiarw.hfg.manager.api;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/monitoring")
class MonitoringController {
  private final RestClient prometheus;

  MonitoringController(
      RestClient.Builder builder, @Value("${hfg.prometheus.base-url}") String baseUrl) {
    this.prometheus = builder.baseUrl(baseUrl).build();
  }

  @GetMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
  String query(@RequestParam String query, @RequestParam(required = false) Instant time) {
    validate(query);
    return prometheus
        .get()
        .uri(
            b ->
                b.path("/api/v1/query")
                    .queryParam("query", query)
                    .queryParamIfPresent("time", java.util.Optional.ofNullable(time))
                    .build())
        .retrieve()
        .body(String.class);
  }

  @GetMapping(value = "/query-range", produces = MediaType.APPLICATION_JSON_VALUE)
  String range(
      @RequestParam String query,
      @RequestParam Instant start,
      @RequestParam Instant end,
      @RequestParam(defaultValue = "60") int step) {
    validate(query);
    if (end.isBefore(start) || end.minusSeconds(31L * 86400).isAfter(start))
      throw new IllegalArgumentException("Range must be at most 31 days");
    if (step < 1) throw new IllegalArgumentException("step must be positive");
    return prometheus
        .get()
        .uri(
            b ->
                b.path("/api/v1/query_range")
                    .queryParam("query", query)
                    .queryParam("start", start.getEpochSecond())
                    .queryParam("end", end.getEpochSecond())
                    .queryParam("step", step)
                    .build())
        .retrieve()
        .body(String.class);
  }

  private static void validate(String query) {
    if (query == null || query.isBlank() || query.length() > 4096)
      throw new IllegalArgumentException("Invalid PromQL query");
  }
}
