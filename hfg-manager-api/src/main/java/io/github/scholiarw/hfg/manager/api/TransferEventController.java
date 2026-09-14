package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.TransferEvent;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/control/transfer-events")
class TransferEventController {
  private final BusinessLogService logs;

  TransferEventController(BusinessLogService logs) { this.logs = logs; }

  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void ingest(@RequestBody List<TransferEvent> events) {
    if (events.size() > 1000) throw new IllegalArgumentException("Maximum batch size is 1000");
    for (TransferEvent event : events) logs.ingest(event);
  }
}
