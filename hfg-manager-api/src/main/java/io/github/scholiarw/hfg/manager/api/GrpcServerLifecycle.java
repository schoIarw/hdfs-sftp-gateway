package io.github.scholiarw.hfg.manager.api;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
class GrpcServerLifecycle implements SmartLifecycle {
  private final boolean enabled;
  private final int port;
  private final String cert, key, ca;
  private final ControlPlaneGrpcService service;
  private volatile Server server;

  GrpcServerLifecycle(
      @Value("${hfg.rpc.enabled:true}") boolean enabled,
      @Value("${hfg.rpc.port:19090}") int port,
      @Value("${hfg.rpc.server-certificate:/etc/hfg/pki/manager.crt}") String cert,
      @Value("${hfg.rpc.server-private-key:/etc/hfg/pki/manager.key}") String key,
      @Value("${hfg.rpc.ca-certificate:/etc/hfg/pki/ca.crt}") String ca,
      ControlPlaneGrpcService service) {
    this.enabled = enabled;
    this.port = port;
    this.cert = cert;
    this.key = key;
    this.ca = ca;
    this.service = service;
  }

  public void start() {
    if (!enabled) return;
    try {
      var ssl =
          GrpcSslContexts.forServer(new File(cert), new File(key))
              .trustManager(new File(ca))
              .clientAuth(ClientAuth.REQUIRE)
              .build();
      server =
          NettyServerBuilder.forPort(port)
              .sslContext(ssl)
              .maxInboundMessageSize(34 * 1024 * 1024)
              .addService(ServerInterceptors.intercept(service, new GatewayIdentityInterceptor()))
              .build()
              .start();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot start HFG mTLS gRPC server", e);
    }
  }

  public void stop() {
    if (server != null)
      try {
        server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
  }

  public boolean isRunning() {
    return server != null && !server.isShutdown();
  }

  public boolean isAutoStartup() {
    return true;
  }

  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
