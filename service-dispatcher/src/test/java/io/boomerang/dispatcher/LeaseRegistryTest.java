package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LeaseRegistryTest {

  private final LeaseRegistry leaseRegistry = new LeaseRegistry();

  @Test
  void aliveWithinExcludesStampsOlderThanTheWindow() throws InterruptedException {
    leaseRegistry.beat("task-old");
    Thread.sleep(50);

    assertTrue(leaseRegistry.aliveWithin(Duration.ofMillis(200)).contains("task-old"));
    assertFalse(leaseRegistry.aliveWithin(Duration.ofMillis(10)).contains("task-old"));
  }

  @Test
  void removeDropsTheEntry() {
    leaseRegistry.beat("task-1");
    assertTrue(leaseRegistry.aliveWithin(Duration.ofMinutes(1)).contains("task-1"));

    leaseRegistry.remove("task-1");

    assertFalse(leaseRegistry.aliveWithin(Duration.ofMinutes(1)).contains("task-1"));
  }
}
