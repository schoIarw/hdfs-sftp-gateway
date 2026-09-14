package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.TransferEvent;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/control/transfer-events")
class TransferEventController {
  private final JdbcClient db;

  TransferEventController(JdbcClient db) {
    this.db = db;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void ingest(@RequestBody List<TransferEvent> events) {
    if (events.size() > 1000) throw new IllegalArgumentException("Maximum batch size is 1000");
    for (TransferEvent e : events) {
      long seq = e.status().ordinal();
      db.sql(
              "insert into transfer_event(transfer_id,event_sequence,user_id,protocol,direction,status,virtual_path,bytes,gateway_id,client_address,error_code,correlation_id,occurred_at) values(:id,:seq,:u,:p,:d,:s,:path,:b,:g,:client,:error,:c,:at) on conflict(transfer_id,event_sequence) do nothing")
          .param("id", e.transferId())
          .param("seq", seq)
          .param("u", e.userId())
          .param("p", e.protocol().name())
          .param("d", e.direction().name())
          .param("s", e.status().name())
          .param("path", e.virtualPath())
          .param("b", e.bytes())
          .param("g", e.gatewayId())
          .param("client", e.clientAddress())
          .param("error", e.errorCode() == null ? null : e.errorCode().name())
          .param("c", e.correlationId())
          .param("at", Timestamp.from(e.occurredAt()))
          .update();
    }
  }
}
