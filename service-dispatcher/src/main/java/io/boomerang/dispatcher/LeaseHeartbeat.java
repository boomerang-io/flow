package io.boomerang.dispatcher;

import io.boomerang.client.EngineClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically reports the TaskRun ids this dispatcher's watch loops are still polling, so the
 * engine can renew their lease rather than reap a task that is merely slow.
 */
@Component
@ConditionalOnProperty(
    name = "flow.dispatcher.lease.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LeaseHeartbeat {

  private final LeaseRegistry leaseRegistry;

  private final EngineClient engineClient;

  @Value("${flow.dispatcher.lease.beat-ms:30000}")
  private long beatMs;

  public LeaseHeartbeat(LeaseRegistry leaseRegistry, EngineClient engineClient) {
    this.leaseRegistry = leaseRegistry;
    this.engineClient = engineClient;
  }

  @Scheduled(fixedDelayString = "${flow.dispatcher.lease.beat-ms:30000}")
  public void beat() {
    // A watch loop beats the registry once per reconcile interval; a TaskRun is still alive if
    // it beat within one and a half beat intervals of now.
    List<String> ids = leaseRegistry.aliveWithin(Duration.ofMillis(beatMs + beatMs / 2));
    if (!ids.isEmpty()) {
      engineClient.heartbeat(ids);
    }
  }
}
