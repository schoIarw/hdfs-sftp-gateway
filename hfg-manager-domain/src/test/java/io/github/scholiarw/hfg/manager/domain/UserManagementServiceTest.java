package io.github.scholiarw.hfg.manager.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UserManagementServiceTest {
  @Test
  void hashesPasswordAndUsesOptimisticRevision() {
    var repo = new MemoryRepo();
    var service =
        new UserManagementService(
            repo,
            new PasswordHasher() {
              public String hash(CharSequence s) {
                return "h:" + s;
              }

              public boolean matches(CharSequence s, String h) {
                return h.equals("h:" + s);
              }
            },
            Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC));
    var user =
        service.create(
            new UserManagementService.CreateUser(
                "user_01", "secret", "d", "b", null, null, null, "g1", null));
    assertEquals("h:secret", user.passwordHash());
    assertThrows(
        IllegalStateException.class,
        () ->
            service.update(
                user.id(),
                9,
                new UserManagementService.UpdateUser("d", "b", null, null, null, "g1", null)));
  }

  static final class MemoryRepo implements UserRepository {
    final Map<UUID, ManagedUser> data = new HashMap<>();

    public Optional<ManagedUser> findById(UUID id) {
      return Optional.ofNullable(data.get(id));
    }

    public Optional<ManagedUser> findByUsername(String n) {
      return data.values().stream().filter(u -> u.username().equals(n)).findFirst();
    }

    public List<ManagedUser> findAll(int o, int l, String q) {
      return data.values().stream().skip(o).limit(l).toList();
    }

    public long count(String q) {
      return data.size();
    }

    public ManagedUser save(ManagedUser u) {
      data.put(u.id(), u);
      return u;
    }

    public void delete(UUID id) {
      data.remove(id);
    }
  }
}
