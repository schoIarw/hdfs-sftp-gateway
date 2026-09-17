package io.github.scholiarw.hfg.gateway;

import io.github.scholiarw.hfg.contract.*;
import io.github.scholiarw.hfg.control.GrpcControlClient;
import io.github.scholiarw.hfg.transfer.QuotaLeaseClient;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

@Component
class HttpQuotaLeaseClient implements QuotaLeaseClient {
  private final GrpcControlClient control;

  HttpQuotaLeaseClient(GrpcControlClient control) {
    this.control = control;
  }

  public Lease reserve(UserSnapshot user, TransferDirection direction, long files, long bytes) {
    try {
      var reservation = control.reserveQuota(user.id(), direction.name(), files, bytes);
      return new Lease(reservation.id(), reservation.files(), reservation.bytes());
    } catch (StatusRuntimeException exception) {
      throw quotaError(exception);
    }
  }

  @Override
  public void renew(Lease lease) {
    try {
      control.renewQuota(lease.id());
    } catch (StatusRuntimeException exception) {
      throw quotaError(exception);
    }
  }

  public void commit(Lease lease, long files, long bytes) {
    try {
      control.commitQuota(lease.id(), files, bytes);
    } catch (StatusRuntimeException exception) {
      throw quotaError(exception);
    }
  }

  private static HfgException quotaError(StatusRuntimeException exception) {
    String detail = exception.getStatus().getDescription();
    return new HfgException(
        HfgErrorCode.QUOTA_EXCEEDED,
        detail == null || detail.isBlank() ? "Manager rejected quota reservation" : detail);
  }
}
