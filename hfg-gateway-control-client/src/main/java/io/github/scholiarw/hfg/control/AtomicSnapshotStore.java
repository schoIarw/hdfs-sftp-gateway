package io.github.scholiarw.hfg.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AtomicSnapshotStore implements UserSnapshotProvider {
  private final Path path;
  private final ObjectMapper mapper;
  private final SnapshotVerifier verifier;
  private final String serviceGroupId;
  private final AtomicReference<SnapshotPayload> current = new AtomicReference<>();

  public AtomicSnapshotStore(
      Path path, ObjectMapper mapper, SnapshotVerifier verifier, String serviceGroupId) {
    this.path = path;
    this.mapper = mapper;
    this.verifier = verifier;
    this.serviceGroupId = serviceGroupId;
  }

  public synchronized boolean install(SignedSnapshotEnvelope envelope) throws IOException {
    SnapshotPayload next = verifier.verify(envelope);
    SnapshotPayload previous = current.get();
    if (!serviceGroupId.equals(next.serviceGroupId()))
      throw new HfgException(
          HfgErrorCode.CONFIG_INVALID, "Snapshot belongs to another service group");
    if (previous != null && next.version() <= previous.version()) return false;
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temp = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(
        temp,
        mapper.writeValueAsString(envelope),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }
    current.set(next);
    return true;
  }

  public synchronized boolean loadIfPresent() throws IOException {
    if (!Files.exists(path)) return false;
    SignedSnapshotEnvelope envelope =
        mapper.readValue(Files.readString(path), SignedSnapshotEnvelope.class);
    current.set(verifier.verify(envelope));
    return true;
  }

  public long version() {
    SnapshotPayload value = current.get();
    return value == null ? 0 : value.version();
  }

  @Override
  public Optional<UserSnapshot> findByUsername(String username) {
    SnapshotPayload value = current.get();
    return value == null
        ? Optional.empty()
        : value.users().stream().filter(u -> u.username().equals(username)).findFirst();
  }

  @Override
  public Collection<UserSnapshot> allUsers() {
    SnapshotPayload value = current.get();
    return value == null ? List.of() : value.users();
  }
}
