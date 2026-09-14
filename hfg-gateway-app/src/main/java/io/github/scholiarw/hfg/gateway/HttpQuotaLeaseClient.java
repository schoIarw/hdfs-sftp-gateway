package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.transfer.QuotaLeaseClient;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class HttpQuotaLeaseClient implements QuotaLeaseClient {
  private final GatewayProperties p;
  private final RestClient client;

  HttpQuotaLeaseClient(GatewayProperties p, RestClient.Builder builder) {
    this.p = p;
    this.client = builder.build();
  }

  public Lease reserve(UserSnapshot user, TransferDirection direction, long files, long bytes) {
    if (p.snapshot().managerUrl() == null || p.snapshot().managerUrl().isBlank())
      throw new HfgException(
          HfgErrorCode.QUOTA_EXCEEDED, "Manager is required to reserve periodic quota");
    Response r =
        client
            .post()
            .uri(p.snapshot().managerUrl() + "/api/v1/control/quota-reservations")
            .headers(h -> h.setBasicAuth(p.snapshot().username(), p.snapshot().password()))
            .body(new Request(user.id(), direction, files, bytes))
            .retrieve()
            .body(Response.class);
    if (r == null) throw new HfgException(HfgErrorCode.QUOTA_EXCEEDED, "Empty quota response");
    return new Lease(r.id(), r.files(), r.bytes());
  }

  @Override
  public void renew(Lease lease) {
    client
        .post()
        .uri(
            p.snapshot().managerUrl()
                + "/api/v1/control/quota-reservations/"
                + lease.id()
                + "/renew")
        .headers(h -> h.setBasicAuth(p.snapshot().username(), p.snapshot().password()))
        .retrieve()
        .toBodilessEntity();
  }

  public void commit(Lease lease, long files, long bytes) {
    client
        .post()
        .uri(
            p.snapshot().managerUrl()
                + "/api/v1/control/quota-reservations/"
                + lease.id()
                + "/commit")
        .headers(h -> h.setBasicAuth(p.snapshot().username(), p.snapshot().password()))
        .body(new Commit(files, bytes))
        .retrieve()
        .toBodilessEntity();
  }

  record Request(UUID userId, TransferDirection direction, long files, long bytes) {}

  record Response(UUID id, long files, long bytes) {}

  record Commit(long completedFiles, long completedBytes) {}
}
