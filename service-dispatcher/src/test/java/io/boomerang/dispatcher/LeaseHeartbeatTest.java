package io.boomerang.dispatcher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.client.EngineClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeaseHeartbeatTest {

  private final LeaseRegistry leaseRegistry = mock(LeaseRegistry.class);
  private final EngineClient engineClient = mock(EngineClient.class);

  private final LeaseHeartbeat leaseHeartbeat = new LeaseHeartbeat(leaseRegistry, engineClient);

  @Test
  void emptyRegistrySendsNoHeartbeat() {
    when(leaseRegistry.aliveWithin(org.mockito.ArgumentMatchers.any(Duration.class)))
        .thenReturn(List.of());

    leaseHeartbeat.beat();

    verify(engineClient, never()).heartbeat(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void nonEmptyRegistrySendsOneBatchedHeartbeat() {
    when(leaseRegistry.aliveWithin(org.mockito.ArgumentMatchers.any(Duration.class)))
        .thenReturn(List.of("task-1", "task-2"));

    leaseHeartbeat.beat();

    verify(engineClient).heartbeat(List.of("task-1", "task-2"));
  }
}
