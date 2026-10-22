package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.util.SweepRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A watcher cycle runs its sweeps in sequence. The paged queries behind them carry a server-side
 * time limit that throws rather than returning a short page, so an unisolated failure in an early
 * sweep would stop every sweep after it - silently ending timeout reaping and stale-claim recovery
 * on the engine watcher, and all cron firing on the schedule watcher.
 */
class WatcherSweepIsolationTest {

  @Test
  void aFailingSweepDoesNotStopTheSweepsAfterIt() {
    List<String> ran = new ArrayList<>();
    List<String> failed = new ArrayList<>();

    SweepRunner.runIsolated("first", () -> ran.add("first"), (name, ex) -> failed.add(name));
    SweepRunner.runIsolated(
        "second",
        () -> {
          throw new IllegalStateException("query exceeded its time limit");
        },
        (name, ex) -> failed.add(name));
    SweepRunner.runIsolated("third", () -> ran.add("third"), (name, ex) -> failed.add(name));

    assertEquals(List.of("first", "third"), ran, "the sweep after the failure must still run");
    assertEquals(List.of("second"), failed);
  }

  @Test
  void theFailureHandlerReceivesTheSweepNameAndCause() {
    List<String> names = new ArrayList<>();
    List<Exception> causes = new ArrayList<>();

    SweepRunner.runIsolated(
        "reapTaskTimeouts",
        () -> {
          throw new IllegalStateException("boom");
        },
        (name, ex) -> {
          names.add(name);
          causes.add(ex);
        });

    assertEquals(List.of("reapTaskTimeouts"), names);
    assertEquals(1, causes.size());
    assertTrue(causes.get(0) instanceof IllegalStateException);
    assertEquals("boom", causes.get(0).getMessage());
  }

  @Test
  void anErrorIsNotSwallowed() {
    // Only Exception is isolated. An Error (OOM, StackOverflow) must still propagate - swallowing
    // one would let the watcher keep looping against a broken JVM.
    List<String> failed = new ArrayList<>();
    try {
      SweepRunner.runIsolated(
          "fatal",
          () -> {
            throw new StackOverflowError();
          },
          (name, ex) -> failed.add(name));
      assertTrue(false, "an Error must propagate rather than being isolated");
    } catch (StackOverflowError expected) {
      assertTrue(failed.isEmpty());
    }
  }
}
