package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A wait on the monitor ends at a signal since the generation it was given, at the interval, or at
 * an interrupt; a signal that landed before the wait began, after the generation was read, ends
 * it at once.
 */
class PollWakeupsTest {

  private final PollWakeups wakeups = new PollWakeups();

  private Void awaitFiveSeconds() throws InterruptedException {
    wakeups.awaitChange(wakeups.generation(), Duration.ofSeconds(5));
    return null;
  }

  @Test
  void aSignalEndsTheWaitBeforeTheInterval() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      long started = System.nanoTime();
      Future<?> waiting = executor.submit(this::awaitFiveSeconds);
      Thread.sleep(100);
      wakeups.signal();
      waiting.get(1, TimeUnit.SECONDS);
      long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs < 1000, "woke " + waitedMs + " ms after the wait began");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void withoutASignalTheWaitEndsAtTheInterval() throws Exception {
    long started = System.nanoTime();
    wakeups.awaitChange(wakeups.generation(), Duration.ofMillis(200));
    long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertTrue(waitedMs >= 190, "waited only " + waitedMs + " ms");
  }

  @Test
  void aSignalBetweenReadingTheGenerationAndWaitingEndsTheWaitAtOnce() throws Exception {
    long seen = wakeups.generation();
    wakeups.signal();
    long started = System.nanoTime();
    wakeups.awaitChange(seen, Duration.ofSeconds(5));
    long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertTrue(waitedMs < 1000, "waited " + waitedMs + " ms for a signal already landed");
  }

  @Test
  void anInterruptEndsTheWait() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> waiting = executor.submit(this::awaitFiveSeconds);
      Thread.sleep(100);
      executor.shutdownNow();
      Exception ended = assertThrows(Exception.class, () -> waiting.get(1, TimeUnit.SECONDS));
      assertTrue(
          ended.getCause() instanceof InterruptedException, String.valueOf(ended.getCause()));
    } finally {
      executor.shutdownNow();
    }
  }
}
