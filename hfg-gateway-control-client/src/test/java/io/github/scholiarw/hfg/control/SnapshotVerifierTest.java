package io.github.scholiarw.hfg.control;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.scholiarw.hfg.contract.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotVerifierTest {
  @Test
  void verifiesDigestSignatureAndRejectsTampering() throws Exception {
    var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String payload =
        mapper.writeValueAsString(new SnapshotPayload(1, "g1", Instant.EPOCH, List.of()));
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(keys.getPrivate());
    s.update(bytes);
    var envelope =
        new SignedSnapshotEnvelope(payload, hash, Base64.getEncoder().encodeToString(s.sign()));
    var verifier = new SnapshotVerifier(keys.getPublic(), mapper);
    assertEquals(1, verifier.verify(envelope).version());
    assertThrows(
        HfgException.class,
        () ->
            verifier.verify(
                new SignedSnapshotEnvelope(payload + " ", hash, envelope.signatureBase64())));
  }
}
