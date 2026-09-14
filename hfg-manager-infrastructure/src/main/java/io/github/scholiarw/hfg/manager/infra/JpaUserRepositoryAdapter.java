package io.github.scholiarw.hfg.manager.infra;

import io.github.scholiarw.hfg.manager.domain.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class JpaUserRepositoryAdapter implements UserRepository {
  private final HfgUserJpaRepository repository;

  public JpaUserRepositoryAdapter(HfgUserJpaRepository repository) {
    this.repository = repository;
  }

  public Optional<ManagedUser> findById(UUID id) {
    return repository.findById(id).map(this::domain);
  }

  public Optional<ManagedUser> findByUsername(String username) {
    return repository.findByUsername(username).map(this::domain);
  }

  public List<ManagedUser> findAll(int offset, int limit, String query) {
    int page = offset / limit;
    return repository.search(normalize(query), PageRequest.of(page, limit)).stream()
        .map(this::domain)
        .toList();
  }

  public long count(String query) {
    return repository.countSearch(normalize(query));
  }

  public ManagedUser save(ManagedUser user) {
    return domain(repository.saveAndFlush(entity(user, !repository.existsById(user.id()))));
  }

  public void delete(UUID id) {
    repository.deleteById(id);
  }

  private static String normalize(String q) {
    return q == null ? "" : q.trim();
  }

  private ManagedUser domain(HfgUserEntity e) {
    return new ManagedUser(
        e.id,
        e.username,
        e.passwordHash,
        e.department,
        e.businessDomain,
        e.phone,
        e.email,
        e.note,
        e.status,
        e.serviceGroupId,
        e.hdfsEffectiveUser,
        e.expiresAt,
        e.createdAt,
        e.updatedAt,
        e.revision == null ? 0 : e.revision);
  }

  private HfgUserEntity entity(ManagedUser u, boolean isNew) {
    var e = new HfgUserEntity();
    e.id = u.id();
    e.username = u.username();
    e.passwordHash = u.passwordHash();
    e.department = u.department();
    e.businessDomain = u.businessDomain();
    e.phone = u.phone();
    e.email = u.email();
    e.note = u.note();
    e.status = u.status();
    e.serviceGroupId = u.serviceGroupId();
    e.hdfsEffectiveUser = u.hdfsEffectiveUser();
    e.expiresAt = u.expiresAt();
    e.createdAt = u.createdAt();
    e.updatedAt = u.updatedAt();
    e.revision = isNew ? null : u.revision();
    return e;
  }
}
