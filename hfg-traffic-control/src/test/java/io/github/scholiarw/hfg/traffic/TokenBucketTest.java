package io.github.scholiarw.hfg.traffic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {
  @Test
  void refillsDeterministically() {
    AtomicLong nanos = new AtomicLong();
    var bucket = new TokenBucket(100, 100, nanos::get);
    assertTrue(bucket.tryAcquire(100));
    assertFalse(bucket.tryAcquire(1));
    nanos.addAndGet(500_000_000L);
    assertTrue(bucket.tryAcquire(50));
  }
}
