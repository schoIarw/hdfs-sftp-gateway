package io.github.scholiarw.hfg.manager.domain;

import java.util.*;

public interface UserRepository {
  Optional<ManagedUser> findById(UUID id);

  Optional<ManagedUser> findByUsername(String username);

  List<ManagedUser> findAll(int offset, int limit, String query);

  long count(String query);

  ManagedUser save(ManagedUser user);

  void delete(UUID id);
}
