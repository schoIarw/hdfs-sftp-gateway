package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/settings")
class SystemSettingsController {
  private static final String MONITORING_PANEL = "monitoring_panel_enabled";
  private final JdbcClient db;
  private final DatabaseDialect dialect;

  SystemSettingsController(JdbcClient db, DatabaseDialect dialect) {
    this.db = db;
    this.dialect = dialect;
  }

  @GetMapping
  Map<String, Object> get() {
    return Map.of("monitoringPanelEnabled", booleanSetting(MONITORING_PANEL, true));
  }

  @PutMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void put(@Valid @RequestBody SettingsRequest request) {
    db.sql(
            dialect.choose(
                "insert into system_setting(setting_key,setting_value,updated_at) values(:key,:value,:now) on conflict(setting_key) do update set setting_value=excluded.setting_value,updated_at=excluded.updated_at",
                "insert into system_setting(setting_key,setting_value,updated_at) values(:key,:value,:now) on duplicate key update setting_value=values(setting_value),updated_at=values(updated_at)"))
        .param("key", MONITORING_PANEL)
        .param("value", request.monitoringPanelEnabled().toString())
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
  }

  private boolean booleanSetting(String key, boolean fallback) {
    return db.sql("select setting_value from system_setting where setting_key=:key")
        .param("key", key)
        .query(String.class)
        .optional()
        .map(Boolean::parseBoolean)
        .orElse(fallback);
  }

  record SettingsRequest(@NotNull Boolean monitoringPanelEnabled) {}
}
