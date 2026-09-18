package io.github.scholiarw.hfg.manager.api;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MonitoringControllerTest {
  private static final String BASE = "http://prometheus.test:9090";
  private static final String HEALTH = BASE + "/-/healthy";

  private static MonitoringController controller(
      RestClient.Builder builder, boolean enabled, String user, String password, String token) {
    return new MonitoringController(builder, enabled, BASE, user, password, token, null, null);
  }

  private static MockRestServiceServer server(RestClient.Builder builder) {
    return MockRestServiceServer.bindTo(builder).build();
  }

  @Test
  void reportsTheFeatureAsDisabledAndRefusesQueries() {
    MonitoringController controller = controller(RestClient.builder(), false, "", "", "");
    Map<String, Object> status = controller.status();
    assertFalse((Boolean) status.get("enabled"));
    assertFalse((Boolean) status.get("reachable"));
    assertTrue(String.valueOf(status.get("message")).contains("未开启"));
    assertThrows(PrometheusNotEnabledException.class, () -> controller.query("up", null));
  }

  @Test
  void usesABearerTokenWhenConfigured() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = server(builder);
    server
        .expect(requestTo(HEALTH))
        .andExpect(header("Authorization", "Bearer s3cret"))
        .andRespond(withSuccess("Prometheus is Healthy.", MediaType.TEXT_PLAIN));
    server
        .expect(requestTo(BASE + "/api/v1/query?query=up"))
        .andExpect(header("Authorization", "Bearer s3cret"))
        .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));
    MonitoringController controller = controller(builder, true, "", "", "s3cret");
    Map<String, Object> status = controller.status();
    assertEquals("bearer", status.get("authentication"));
    assertEquals(true, status.get("reachable"));
    assertEquals("{\"status\":\"success\"}", controller.query("up", null));
    server.verify();
  }

  @Test
  void usesBasicCredentialsWhenConfigured() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = server(builder);
    server
        .expect(requestTo(HEALTH))
        .andExpect(header("Authorization", startsWith("Basic ")))
        .andRespond(withSuccess("Prometheus is Healthy.", MediaType.TEXT_PLAIN));
    server
        .expect(requestTo(BASE + "/api/v1/query_range?query=up&start=0&end=60&step=60"))
        .andExpect(header("Authorization", startsWith("Basic ")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    MonitoringController controller = controller(builder, true, "ops", "pw", "");
    assertEquals("basic", controller.status().get("authentication"));
    controller.range("up", Instant.ofEpochSecond(0), Instant.ofEpochSecond(60), 60);
    server.verify();
  }

  @Test
  void sendsNoCredentialsWhenAnonymous() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = server(builder);
    server.expect(requestTo(HEALTH)).andRespond(withSuccess("OK", MediaType.TEXT_PLAIN));
    server
        .expect(requestTo(BASE + "/api/v1/query?query=up"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    MonitoringController controller = controller(builder, true, "", "", "");
    assertEquals("none", controller.status().get("authentication"));
    controller.query("up", null);
    server.verify();
  }

  @Test
  void reportsAnUnreachablePrometheusWithoutFailingTheStatusCall() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = server(builder);
    server.expect(requestTo(HEALTH)).andRespond(withServerError());
    MonitoringController controller = controller(builder, true, "", "", "");
    Map<String, Object> status = controller.status();
    assertEquals(false, status.get("reachable"));
    assertTrue(String.valueOf(status.get("message")).contains("无法连接"));
    server.verify();
  }

  @Test
  void rejectsAnEmptyUrlWhenEnabled() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new MonitoringController(
                    RestClient.builder(),
                    true,
                    "",
                    "",
                    "",
                    "token",
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(2)));
    assertTrue(failure.getMessage().contains("HFG_PROMETHEUS_URL"));
  }
}
