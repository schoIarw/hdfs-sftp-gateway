package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.TransferDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/control/quota-reservations")
class QuotaReservationController {
  private final QuotaReservationService service;

  QuotaReservationController(QuotaReservationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  QuotaReservationService.Reservation reserve(@Valid @RequestBody Reserve r) {
    return service.reserve(r.userId(), r.direction(), r.files(), r.bytes());
  }

  @PostMapping("/{id}/commit")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void commit(@PathVariable UUID id, @Valid @RequestBody Commit r) {
    service.commit(id, r.completedFiles(), r.completedBytes());
  }

  @PostMapping("/{id}/renew")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void renew(@PathVariable UUID id) {
    service.renew(id);
  }

  record Reserve(
      @NotNull UUID userId,
      @NotNull TransferDirection direction,
      @Min(0) long files,
      @Min(0) long bytes) {}

  record Commit(@Min(0) long completedFiles, @Min(0) long completedBytes) {}
}
