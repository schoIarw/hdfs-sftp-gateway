package io.github.scholiarw.hfg.storage;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface StorageWriteHandle extends AutoCloseable {
  int write(ByteBuffer source) throws IOException;

  void flush() throws IOException;

  long position();

  @Override
  void close() throws IOException;
}
