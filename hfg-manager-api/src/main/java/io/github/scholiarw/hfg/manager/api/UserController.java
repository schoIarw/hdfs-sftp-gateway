package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.manager.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
class UserController {
  private final UserManagementService service;
  private final JdbcClient db;
  private final DatabaseDialect dialect;

  UserController(UserManagementService service, JdbcClient db, DatabaseDialect dialect) {
    this.service = service;
    this.db = db;
    this.dialect = dialect;
  }

  @GetMapping
  UserPage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String query) {
    var result = service.list(page, size, query);
    return new UserPage(
        result.items().stream().map(UserView::from).toList(),
        result.total(),
        result.page(),
        result.size());
  }

  @GetMapping("/{id}")
  UserView get(@PathVariable UUID id) {
    return UserView.from(service.require(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  UserView create(@Valid @RequestBody CreateRequest r) {
    return UserView.from(
        service.create(
            new UserManagementService.CreateUser(
                r.username(),
                r.password(),
                r.department(),
                r.businessDomain(),
                r.phone(),
                r.email(),
                r.note(),
                r.serviceGroupId(),
                r.expiresAt())));
  }

  @PutMapping("/{id}")
  UserView update(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long revision,
      @Valid @RequestBody UpdateRequest r) {
    ClusterBindingGuard.requireOwnedDirectoriesMatchGroup(db, dialect, id, r.serviceGroupId());
    return UserView.from(
        service.update(
            id,
            revision,
            new UserManagementService.UpdateUser(
                r.department(),
                r.businessDomain(),
                r.phone(),
                r.email(),
                r.note(),
                r.serviceGroupId(),
                r.expiresAt())));
  }

  @PutMapping("/{id}/status")
  UserView status(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long revision,
      @RequestBody StatusRequest r) {
    return UserView.from(service.changeStatus(id, revision, r.status()));
  }

  @PutMapping("/{id}/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void password(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long revision,
      @Valid @RequestBody PasswordRequest r) {
    service.resetPassword(id, revision, r.password());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    var directories =
        db.sql("select name from directory_mapping where owner_user_id=:user order by name")
            .param("user", dialect.id(id))
            .query(String.class)
            .list();
    if (!directories.isEmpty())
      throw new IllegalStateException(
          "用户仍归属目录：" + String.join("、", directories) + "；请先删除目录或将目录转移给其他用户");
    service.delete(id);
  }

  record CreateRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
      @NotBlank @Size(min = 12, max = 128) String password,
      @Size(max = 128) String department,
      @Size(max = 128) String businessDomain,
      @Size(max = 64) String phone,
      @Email @Size(max = 255) String email,
      @Size(max = 1024) String note,
      @NotBlank String serviceGroupId,
      Instant expiresAt) {}

  record UpdateRequest(
      @Size(max = 128) String department,
      @Size(max = 128) String businessDomain,
      @Size(max = 64) String phone,
      @Email @Size(max = 255) String email,
      @Size(max = 1024) String note,
      @NotBlank String serviceGroupId,
      Instant expiresAt) {}

  record StatusRequest(@NotNull AccountStatus status) {}

  record PasswordRequest(@NotBlank @Size(min = 12, max = 128) String password) {}

  record UserPage(java.util.List<UserView> items, long total, int page, int size) {}

  record UserView(
      UUID id,
      String username,
      String department,
      String businessDomain,
      String phone,
      String email,
      String note,
      AccountStatus status,
      String serviceGroupId,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt,
      long revision) {
    static UserView from(ManagedUser u) {
      return new UserView(
          u.id(),
          u.username(),
          u.department(),
          u.businessDomain(),
          u.phone(),
          u.email(),
          u.note(),
          u.status(),
          u.serviceGroupId(),
          u.expiresAt(),
          u.createdAt(),
          u.updatedAt(),
          u.revision());
    }
  }
}
