package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alerts/rules")
class AlertRuleController {
  private final JdbcClient db;

  AlertRuleController(JdbcClient db) {
    this.db = db;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    return db.sql("select * from alert_rule order by name").query().listOfRows();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(@Valid @RequestBody Rule r) {
    UUID id = UUID.randomUUID();
    Instant n = Instant.now();
    db.sql(
            "insert into alert_rule(id,name,promql,duration_seconds,severity,labels_json,annotations_json,enabled,created_at,updated_at) values(:id,:name,:q,:duration,:severity,:labels,:annotations,:enabled,:n,:n)")
        .param("id", id)
        .param("name", r.name())
        .param("q", r.promql())
        .param("duration", r.durationSeconds())
        .param("severity", r.severity())
        .param("labels", r.labelsJson() == null ? "{}" : r.labelsJson())
        .param("annotations", r.annotationsJson() == null ? "{}" : r.annotationsJson())
        .param("enabled", r.enabled())
        .param("n", n)
        .update();
    return db.sql("select * from alert_rule where id=:id").param("id", id).query().singleRow();
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    db.sql("delete from alert_rule where id=:id").param("id", id).update();
  }

  record Rule(
      @NotBlank @Size(max = 128) String name,
      @NotBlank @Size(max = 4096) String promql,
      @Min(0) int durationSeconds,
      @NotBlank String severity,
      String labelsJson,
      String annotationsJson,
      boolean enabled) {}
}
