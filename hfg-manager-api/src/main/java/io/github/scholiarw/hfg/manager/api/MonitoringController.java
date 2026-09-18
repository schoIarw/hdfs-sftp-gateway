package io.github.scholiarw.hfg.manager.api;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

/**
 * Prometheus pass-through for the management UI.
 *
 * <p>The integration can be switched off with {@code HFG_PROMETHEUS_ENABLED=false}; the UI then
 * reports that the feature is not enabled. Optional authentication uses a bearer token or HTTP
 * basic credentials.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
class MonitoringController {
  private static final Logger log = LoggerFactory.getLogger(MonitoringController.class);
  private final boolean enabled;
  private final String baseUrl;
  private final String authentication;
  private final RestClient prometheus;
  private final RestClient healthProbe;

  MonitoringController(
      RestClient.Builder builder,
      @Value("${hfg.prometheus.enabled:true}") boolean enabled,
      @Value("${hfg.prometheus.base-url:}") String baseUrl,
      @Value("${hfg.prometheus.username:}") String username,
      @Value("${hfg.prometheus.password:}") String password,
      @Value("${hfg.prometheus.token:}") String token,
      @Value("${hfg.prometheus.request-timeout:PT10S}") Duration timeout,
      @Value("${hfg.prometheus.health-timeout:PT2S}") Duration healthTimeout) {
    this.enabled = enabled;
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    String user = username == null ? "" : username.trim();
    String secret = password == null ? "" : password;
    String bearer = token == null ? "" : token.trim();
    this.authentication = !bearer.isEmpty() ? "bearer" : (!user.isEmpty() ? "basic" : "none");
    if (!enabled) {
      this.prometheus = null;
      this.healthProbe = null;
      return;
    }
    if (this.baseUrl.isEmpty())
      throw new IllegalStateException(
          "HFG_PROMETHEUS_URL 未配置：请设置 Prometheus 地址，或将 HFG_PROMETHEUS_ENABLED 设为 false 关闭该功能");
    this.prometheus = configure(builder, user, secret, bearer, timeout).build();
    this.healthProbe = configure(builder, user, secret, bearer, healthTimeout).build();
  }

  private RestClient.Builder configure(
      RestClient.Builder builder, String user, String secret, String bearer, Duration timeout) {
    RestClient.Builder configured = builder.baseUrl(this.baseUrl);
    if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
      configured =
          configured.requestFactory(
              ClientHttpRequestFactoryBuilder.detect()
                  .build(
                      ClientHttpRequestFactorySettings.defaults()
                          .withConnectTimeout(timeout)
                          .withReadTimeout(timeout)));
    }
    if (!bearer.isEmpty()) {
      configured = configured.defaultHeader("Authorization", "Bearer " + bearer);
    } else if (!user.isEmpty()) {
      configured = configured.defaultHeaders(headers -> headers.setBasicAuth(user, secret));
    }
    return configured;
  }

  /** Lets the management UI show whether the feature is available and how it authenticates. */
  @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> status() {
    boolean reachable = enabled && reachable();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("enabled", enabled);
    result.put("reachable", reachable);
    result.put("baseUrl", baseUrl);
    result.put("authentication", authentication);
    result.put(
        "message",
        !enabled
            ? "Prometheus 未开启：请设置 HFG_PROMETHEUS_ENABLED=true 与 HFG_PROMETHEUS_URL"
            : reachable
                ? "Prometheus 查询已启用（认证：" + label() + "）"
                : "Prometheus 已启用但无法连接 " + baseUrl + "：请检查地址、服务状态与认证配置");
    return result;
  }

  /** Cheap reachability probe so the UI can distinguish "off" from "misconfigured". */
  private boolean reachable() {
    try {
      healthProbe.get().uri("/-/healthy").retrieve().toBodilessEntity();
      return true;
    } catch (RuntimeException e) {
      log.debug("Prometheus health probe failed for {}: {}", baseUrl, e.getMessage());
      return false;
    }
  }

  @GetMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
  String query(@RequestParam String query, @RequestParam(required = false) Instant time) {
    requireEnabled();
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
    requireEnabled();
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

  private void requireEnabled() {
    if (!enabled)
      throw new PrometheusNotEnabledException(
          "Prometheus 未开启：请设置 HFG_PROMETHEUS_ENABLED=true（当前配置见 /api/v1/monitoring/status）");
  }

  private String label() {
    return switch (authentication) {
      case "bearer" -> "Bearer Token";
      case "basic" -> "Basic";
      default -> "无";
    };
  }

  private static void validate(String query) {
    if (query == null || query.isBlank() || query.length() > 4096)
      throw new IllegalArgumentException("Invalid PromQL query");
  }
}
