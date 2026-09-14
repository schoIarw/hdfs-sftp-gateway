package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.HfgException;
import java.net.URI;
import java.util.NoSuchElementException;
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

  @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
  ProblemDetail conflict(Exception e) {
    return problem(HttpStatus.CONFLICT, "Conflicting update", e.getMessage());
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
