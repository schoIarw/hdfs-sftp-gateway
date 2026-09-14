package io.github.scholiarw.hfg.contract;

public enum AccessMode {
  NONE,
  READ_ONLY,
  READ_WRITE;

  public boolean canRead() {
    return this != NONE;
  }

  public boolean canWrite() {
    return this == READ_WRITE;
  }
}
