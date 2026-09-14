package io.github.scholiarw.hfg.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.scholiarw.hfg.contract.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;

public final class SnapshotVerifier {
  private final PublicKey publicKey;
  private final ObjectMapper mapper;

  public SnapshotVerifier(PublicKey publicKey, ObjectMapper mapper) {
    this.publicKey = publicKey;
    this.mapper = mapper;
  }

  public SnapshotPayload verify(SignedSnapshotEnvelope envelope) {
    try {
      byte[] payload = envelope.payloadJson().getBytes(StandardCharsets.UTF_8);
      String digest =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
      if (!MessageDigest.isEqual(
          digest.getBytes(StandardCharsets.US_ASCII),
          envelope.payloadSha256().getBytes(StandardCharsets.US_ASCII)))
        throw new HfgException(HfgErrorCode.CONFIG_INVALID, "Snapshot digest mismatch");
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(payload);
      if (!verifier.verify(Base64.getDecoder().decode(envelope.signatureBase64())))
        throw new HfgException(HfgErrorCode.CONFIG_INVALID, "Snapshot signature is invalid");
      return mapper.readValue(payload, SnapshotPayload.class);
    } catch (HfgException e) {
      throw e;
    } catch (Exception e) {
      throw new HfgException(HfgErrorCode.CONFIG_INVALID, "Cannot verify snapshot", null, e);
    }
  }
}
