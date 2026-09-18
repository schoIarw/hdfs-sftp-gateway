package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.HfgException;
import java.net.URI;
import java.util.NoSuchElementException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class ApiExceptionHandler {
  @ExceptionHandler(NoSuchElementException.class)
  ProblemDetail notFound(Exception e) {
    return problem(HttpStatus.NOT_FOUND, "Resource not found", e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ProblemDetail badRequest(Exception e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail conflict(IllegalStateException e) {
    return problem(HttpStatus.CONFLICT, "Conflicting update", e.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail integrity(DataIntegrityViolationException e) {
    Throwable root = e.getMostSpecificCause();
    // Keep the database reason (unique/foreign key, missing detail) but drop the echoed SQL
    // statement, which only repeats what the caller already knows.
    String detail = root == null ? e.getMessage() : root.getMessage();
    int marker = detail == null ? -1 : detail.indexOf("]; ");
    if (marker >= 0) detail = detail.substring(marker + 3);
    var response = problem(HttpStatus.CONFLICT, "违反数据约束", detail);
    response.setProperty("code", "DATA_INTEGRITY_VIOLATION");
    return response;
  }

  @ExceptionHandler(DataAccessException.class)
  ProblemDetail database(DataAccessException e) {
    Throwable root = e.getMostSpecificCause();
    var detail =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "数据库操作失败",
            root == null ? e.getMessage() : root.getMessage());
    detail.setProperty("code", "DATABASE_ERROR");
    return detail;
  }

  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ProblemDetail prometheusUnavailable(org.springframework.web.client.RestClientException e) {
    Throwable cause = e.getMostSpecificCause();
    String reason = cause == null ? null : cause.getMessage();
    if (reason == null || reason.isBlank()) reason = e.getMessage();
    if (reason == null || reason.isBlank())
      reason = cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName();
    var detail = problem(HttpStatus.BAD_GATEWAY, "Prometheus 不可用", "无法访问 Prometheus：" + reason);
    detail.setProperty("code", "PROMETHEUS_UNAVAILABLE");
    return detail;
  }

  @ExceptionHandler(PrometheusNotEnabledException.class)
  ProblemDetail prometheusDisabled(PrometheusNotEnabledException e) {
    var detail = problem(HttpStatus.SERVICE_UNAVAILABLE, "Prometheus 未开启", e.getMessage());
    detail.setProperty("code", "PROMETHEUS_DISABLED");
    return detail;
  }

  @ExceptionHandler(BundleStorageException.class)
  ProblemDetail bundleStorage(BundleStorageException e) {
    var detail = problem(HttpStatus.INTERNAL_SERVER_ERROR, "HDFS 配置包存储失败", e.getMessage());
    detail.setProperty("code", "BUNDLE_STORAGE_ERROR");
    return detail;
  }

  @ExceptionHandler(HfgException.class)
  ProblemDetail hfg(HfgException e) {
    HttpStatus status =
        switch (e.code()) {
          case AUTH_INVALID, ACCOUNT_DISABLED -> HttpStatus.UNAUTHORIZED;
          case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
          case PATH_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
          case HDFS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
          case QUOTA_EXCEEDED, ALREADY_EXISTS, UNSUPPORTED_OFFSET -> HttpStatus.CONFLICT;
          case INVALID_PATH, CONFIG_INVALID -> HttpStatus.BAD_REQUEST;
          case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    var detail = problem(status, e.code().name(), e.getMessage());
    detail.setProperty("code", e.code().name());
    if (e.correlationId() != null) detail.setProperty("correlationId", e.correlationId());
    return detail;
  }

  private ProblemDetail problem(HttpStatus status, String title, String detail) {
    var p = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
    p.setTitle(title);
    p.setType(URI.create("urn:hfg:error:" + status.value()));
    return p;
  }
}
