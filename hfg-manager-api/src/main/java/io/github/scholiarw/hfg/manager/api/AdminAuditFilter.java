package io.github.scholiarw.hfg.manager.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records management-plane mutations without persisting request bodies, passwords, keytabs, or
 * other secret material.
 */
@Component
class AdminAuditFilter extends OncePerRequestFilter {
  private static final String CORRELATION_HEADER = "X-Correlation-Id";
  private final JdbcClient db;

  AdminAuditFilter(JdbcClient db) {
    this.db = db;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    return !request.getRequestURI().startsWith("/api/")
        || (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = correlationId(request);
    response.setHeader(CORRELATION_HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      record(request, response, correlationId);
    }
  }

  private void record(
      HttpServletRequest request, HttpServletResponse response, String correlationId) {
    try {
      Principal principal = request.getUserPrincipal();
      String actor = principal == null ? "anonymous" : principal.getName();
      String path = request.getRequestURI();
      String[] segments = path.split("/");
      String resourceType = segments.length > 3 ? segments[3] : "api";
      String resourceId = segments.length > 4 ? segments[segments.length - 1] : null;
      String action = request.getMethod() + " " + path + " -> " + response.getStatus();
      db.sql(
              "insert into audit_log(id,actor,action,resource_type,resource_id,correlation_id,source_address,occurred_at) values(:id,:actor,:action,:type,:resource,:correlation,:source,:now)")
          .param("id", UUID.randomUUID())
          .param("actor", actor)
          .param("action", action)
          .param("type", resourceType)
          .param("resource", resourceId)
          .param("correlation", correlationId)
          .param("source", request.getRemoteAddr())
          .param("now", java.sql.Timestamp.from(Instant.now()))
          .update();
    } catch (RuntimeException ignored) {
      // Audit persistence must not replace the original API result. Database failures remain
      // visible through datasource and application metrics.
    }
  }

  private static String correlationId(HttpServletRequest request) {
    String supplied = request.getHeader(CORRELATION_HEADER);
    return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
  }
}
