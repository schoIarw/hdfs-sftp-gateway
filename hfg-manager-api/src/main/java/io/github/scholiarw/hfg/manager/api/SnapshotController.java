package io.github.scholiarw.hfg.manager.api;

import io.github.scholiarw.hfg.contract.SignedSnapshotEnvelope;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/control/snapshots")
class SnapshotController {
  private final SnapshotPublisher publisher;
  private final ControlPlaneGrpcService control;

  SnapshotController(SnapshotPublisher publisher, ControlPlaneGrpcService control) {
    this.publisher = publisher;
    this.control = control;
  }

  @PostMapping("/{group}/publish")
  SignedSnapshotEnvelope publish(@PathVariable String group, Principal principal) {
    var envelope = publisher.publish(group, principal.getName());
    control.broadcast(group, envelope);
    return envelope;
  }

  @GetMapping("/{group}/latest")
  SignedSnapshotEnvelope latest(@PathVariable String group) {
    return publisher.latest(group);
  }
}
