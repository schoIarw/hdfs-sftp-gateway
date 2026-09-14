package io.github.scholiarw.hfg.traffic;

@FunctionalInterface
public interface NanoClock {
  long nanoTime();

  static NanoClock system() {
    return System::nanoTime;
  }
}
