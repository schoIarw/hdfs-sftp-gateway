package io.github.scholiarw.hfg.manager.api;

import io.grpc.*;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import javax.naming.ldap.LdapName;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

final class GatewayIdentityInterceptor implements ServerInterceptor {
  static final Context.Key<String> GATEWAY_ID = Context.key("hfg-gateway-id");
  static final Context.Key<String> CERTIFICATE_FINGERPRINT =
      Context.key("hfg-gateway-certificate-fingerprint");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    try {
      SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      if (session == null) return reject(call, "Client certificate is required");
      X509Certificate certificate = (X509Certificate) session.getPeerCertificates()[0];
      String gatewayId = commonName(certificate);
      if (gatewayId == null || gatewayId.isBlank())
        return reject(call, "Gateway identity is missing from the client certificate");
      String fingerprint =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
      return Contexts.interceptCall(
          Context.current()
              .withValue(GATEWAY_ID, gatewayId)
              .withValue(CERTIFICATE_FINGERPRINT, fingerprint),
          call,
          headers,
          next);
    } catch (SSLPeerUnverifiedException
        | java.security.GeneralSecurityException
        | RuntimeException e) {
      return reject(call, "Client certificate cannot be verified");
    }
  }

  private static String commonName(X509Certificate certificate) {
    try {
      return new LdapName(certificate.getSubjectX500Principal().getName())
          .getRdns().stream()
              .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
              .map(rdn -> String.valueOf(rdn.getValue()))
              .findFirst()
              .orElse(null);
    } catch (javax.naming.InvalidNameException e) {
      throw new IllegalArgumentException("Invalid certificate subject", e);
    }
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(
      ServerCall<ReqT, RespT> call, String description) {
    call.close(Status.UNAUTHENTICATED.withDescription(description), new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
