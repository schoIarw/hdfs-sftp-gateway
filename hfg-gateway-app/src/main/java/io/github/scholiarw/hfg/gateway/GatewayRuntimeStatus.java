package io.github.scholiarw.hfg.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
final class GatewayRuntimeStatus {
  private final Map<String, String> errors = new ConcurrentHashMap<>();

  void failed(String component, Exception exception) {
    String message = exception.getMessage();
    errors.put(
        component,
        message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
  }

  void healthy(String component) {
    errors.remove(component);
  }

  String summary() {
    return errors.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + ": " + entry.getValue())
        .reduce((left, right) -> left + "; " + right)
        .map(value -> value.length() > 2000 ? value.substring(0, 2000) : value)
        .orElse("");
  }
}
