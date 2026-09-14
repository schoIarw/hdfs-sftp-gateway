package io.github.scholiarw.hfg.contract;

import java.util.Collection;
import java.util.Optional;

public interface UserSnapshotProvider {
  Optional<UserSnapshot> findByUsername(String username);

  Collection<UserSnapshot> allUsers();
}
