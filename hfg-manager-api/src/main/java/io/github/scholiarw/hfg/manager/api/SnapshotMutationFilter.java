package io.github.scholiarw.hfg.manager.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Schedules a coalesced snapshot publication after a successful configuration mutation. */
@Component
class SnapshotMutationFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(SnapshotMutationFilter.class);
  private final SnapshotPublicationService publications;

  SnapshotMutationFilter(SnapshotPublicationService publications) {
    this.publications = publications;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) return true;
    String path = request.getRequestURI();
    return !(path.startsWith("/api/v1/users")
        || path.startsWith("/api/v1/directories")
        || path.startsWith("/api/v1/system/service-groups"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(request, response);
    if (response.getStatus() < 200 || response.getStatus() >= 300) return;
    Principal principal = request.getUserPrincipal();
    String actor = principal == null ? "system" : principal.getName();
    try {
      publications.requestAll(
          actor, "configuration change: " + request.getMethod() + " " + request.getRequestURI());
    } catch (RuntimeException exception) {
      // The periodic reconciliation task is the durable fallback. A queueing failure must not
      // turn a configuration mutation that was already committed into a false HTTP failure.
      log.warn("Cannot queue automatic snapshot publication", exception);
    }
  }
}
