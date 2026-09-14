package io.github.scholiarw.hfg.storage;

import java.time.Instant;

public record StorageEntry(
    String path,
    String name,
    boolean directory,
    long length,
    Instant modifiedAt,
    String owner,
    String group,
    String permission) {}
