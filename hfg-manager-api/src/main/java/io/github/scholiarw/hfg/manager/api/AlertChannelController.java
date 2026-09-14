package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alerts/channels")
class AlertChannelController {
  private final JdbcClient db;

  AlertChannelController(JdbcClient db) {
    this.db = db;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return db.sql(
            "select id,name,channel_type,config_secret_ref,enabled,created_at,updated_at from alert_channel order by name")
        .query()
        .listOfRows();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(@Valid @RequestBody Channel r) {
    UUID id = UUID.randomUUID();
    Instant n = Instant.now();
    db.sql(
            "insert into alert_channel(id,name,channel_type,config_secret_ref,enabled,created_at,updated_at) values(:id,:name,:type,:secret,:enabled,:n,:n)")
        .param("id", id)
        .param("name", r.name())
        .param("type", r.channelType())
        .param("secret", r.configSecretRef())
        .param("enabled", r.enabled())
        .param("n", n)
        .update();
    return db.sql(
            "select id,name,channel_type,config_secret_ref,enabled,created_at,updated_at from alert_channel where id=:id")
        .param("id", id)
        .query()
        .singleRow();
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    db.sql("delete from alert_channel where id=:id").param("id", id).update();
  }

  record Channel(
      @NotBlank @Size(max = 128) String name,
      @NotBlank @Pattern(regexp = "EMAIL|WEBHOOK|SLACK|PAGERDUTY") String channelType,
      @NotBlank @Size(max = 1024) String configSecretRef,
      boolean enabled) {}
}
