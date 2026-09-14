package io.github.scholiarw.hfg.manager.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.scholiarw.hfg.contract.AccountStatus;
import io.github.scholiarw.hfg.manager.domain.ManagedUser;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JpaUserRepositoryAdapterTest {
  @Test
  void marksAssignedUuidEntityNewWithNullVersion() {
    var user = user(0);
    var repository =
        repository(
            false,
            entity -> {
              assertNull(entity.revision);
              entity.revision = 0L;
            });

    assertEquals(0, new JpaUserRepositoryAdapter(repository).save(user).revision());
  }

  @Test
  void carriesExpectedVersionForExistingEntity() {
    var user = user(7);
    var repository =
        repository(
            true,
            entity -> {
              assertEquals(7L, entity.revision);
              entity.revision++;
            });

    assertEquals(8, new JpaUserRepositoryAdapter(repository).save(user).revision());
  }

  private static HfgUserJpaRepository repository(boolean exists, Consumer<HfgUserEntity> onSave) {
    return (HfgUserJpaRepository)
        Proxy.newProxyInstance(
            HfgUserJpaRepository.class.getClassLoader(),
            new Class<?>[] {HfgUserJpaRepository.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("existsById")) return exists;
              if (method.getName().equals("saveAndFlush")) {
                HfgUserEntity entity = (HfgUserEntity) arguments[0];
                onSave.accept(entity);
                return entity;
              }
              if (method.getName().equals("toString")) return "HfgUserJpaRepositoryTestDouble";
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static ManagedUser user(long revision) {
    Instant now = Instant.parse("2026-09-14T00:00:00Z");
    return new ManagedUser(
        UUID.randomUUID(),
        "user_01",
        "hash",
        null,
        null,
        null,
        null,
        null,
        AccountStatus.ENABLED,
        "group",
        null,
        now,
        now,
        revision);
  }
}
