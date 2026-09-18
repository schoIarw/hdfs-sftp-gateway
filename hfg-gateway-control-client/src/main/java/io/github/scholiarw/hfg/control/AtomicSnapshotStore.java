package io.github.scholiarw.hfg.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AtomicSnapshotStore implements UserSnapshotProvider {
  private static final Logger log = LoggerFactory.getLogger(AtomicSnapshotStore.class);
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

  /**
   * Loads the snapshot cached on disk.
   *
   * <p>A cached snapshot becomes unusable whenever the manager rotates the snapshot signing key or
   * the node is moved to another service group. That must never prevent the gateway from starting:
   * the entry is ignored, a warning is logged and the gateway fetches a fresh snapshot from the
   * control plane.
   */
  public synchronized boolean loadIfPresent() throws IOException {
    if (!Files.exists(path)) return false;
    SignedSnapshotEnvelope envelope;
    try {
      envelope = mapper.readValue(Files.readString(path), SignedSnapshotEnvelope.class);
    } catch (IOException | RuntimeException e) {
      log.warn("Ignoring unreadable cached snapshot {}: {}", path, e.getMessage());
      return false;
    }
    SnapshotPayload payload;
    try {
      payload = verifier.verify(envelope);
    } catch (RuntimeException e) {
      log.warn(
          "Ignoring cached snapshot {}: {}（可能由其它密钥或其它服务组签发，等待控制平面下发新快照）",
          path,
          e.getMessage());
      return false;
    }
    if (!serviceGroupId.equals(payload.serviceGroupId())) {
      log.warn(
          "Ignoring cached snapshot {}: it belongs to service group {} instead of {}",
          path,
          payload.serviceGroupId(),
          serviceGroupId);
      return false;
    }
    current.set(payload);
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
