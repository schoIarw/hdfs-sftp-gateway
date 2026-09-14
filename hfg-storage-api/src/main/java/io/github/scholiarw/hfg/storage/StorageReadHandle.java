package io.github.scholiarw.hfg.storage;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface StorageReadHandle extends AutoCloseable {
  int read(ByteBuffer target) throws IOException;

  void seek(long offset) throws IOException;

  long position() throws IOException;

  @Override
  void close() throws IOException;
}
