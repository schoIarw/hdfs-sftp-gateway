package io.github.scholiarw.hfg.manager.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/ssh-keys")
class SshKeyController {
  private final JdbcClient db;

  SshKeyController(JdbcClient db) {
    this.db = db;
  }

  @GetMapping
  List<Map<String, Object>> list(@PathVariable UUID userId) {
    return db.sql(
            "select id,fingerprint,note,created_at from ssh_public_key where user_id=:u order by created_at")
        .param("u", userId)
        .query()
        .listOfRows();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(@PathVariable UUID userId, @Valid @RequestBody KeyRequest request) {
    String normalized = normalize(request.publicKey());
    String fingerprint = fingerprint(normalized);
    UUID id = UUID.randomUUID();
    db.sql(
            "insert into ssh_public_key(id,user_id,fingerprint,public_key,note,created_at) values(:id,:u,:f,:k,:note,:now)")
        .param("id", id)
        .param("u", userId)
        .param("f", fingerprint)
        .param("k", normalized)
        .param("note", request.note())
        .param("now", java.sql.Timestamp.from(Instant.now()))
        .update();
    return db.sql(
            "select id,fingerprint,note,created_at from ssh_public_key where id=:id and user_id=:u")
        .param("id", id)
        .param("u", userId)
        .query()
        .singleRow();
  }

  @DeleteMapping("/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID userId, @PathVariable UUID keyId) {
    db.sql("delete from ssh_public_key where id=:id and user_id=:u")
        .param("id", keyId)
        .param("u", userId)
        .update();
  }

  static String normalize(String key) {
    String[] parts = key.trim().replaceAll("\\s+", " ").split(" ", 3);
    if (parts.length < 2
        || !(parts[0].startsWith("ssh-")
            || parts[0].startsWith("ecdsa-")
            || parts[0].startsWith("sk-"))) {
      throw new IllegalArgumentException("Invalid OpenSSH public key");
    }
    try {
      Base64.getDecoder().decode(parts[1]);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid OpenSSH public key payload", exception);
    }
    return parts[0] + " " + parts[1] + (parts.length == 3 ? " " + parts[2] : "");
  }

  static String fingerprint(String key) {
    try {
      String payload = key.split(" ", 3)[1];
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(payload));
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Cannot fingerprint OpenSSH public key", exception);
    }
  }

  record KeyRequest(@NotBlank @Size(max = 16_384) String publicKey, @Size(max = 512) String note) {}
}
