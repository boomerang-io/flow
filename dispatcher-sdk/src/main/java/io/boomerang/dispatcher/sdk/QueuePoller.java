package io.boomerang.dispatcher.sdk;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * One long-poll loop on a thread of its own. The engine holds each poll open for up to 30 s, so
 * the next poll starts at once after one that brought runs or was held for its window; after one
 * that failed or was answered empty almost at once - claiming switched off, a filter that matches
 * nothing, an engine that cannot be reached - it starts no sooner than {@link
 * #IDLE_POLL_SPACING} after that poll started, so the engine is never asked in a tight loop. A
 * loop that is not due waits for {@link #wake}, checking again at least every spacing.
 */
final class QueuePoller<T> {

  static final Duration IDLE_POLL_SPACING = Duration.ofSeconds(5);

  private static final Log LOGGER = LogFactory.getLog(QueuePoller.class);

  private final String name;
  private final Supplier<List<T>> poll;
  private final Consumer<T> handOff;
  private final Duration idleSpacing;
  private final BooleanSupplier due;
  private final Semaphore wakeups = new Semaphore(0);

  private volatile boolean running;
  private volatile Thread thread;

  /**
   * Poll with {@code poll}, which blocks for the long poll, and give each run it returns to {@code
   * handOff}, which MUST return at once: the next poll waits on it.
   */
  QueuePoller(String name, Supplier<List<T>> poll, Consumer<T> handOff) {
    this(name, poll, handOff, IDLE_POLL_SPACING);
  }

  QueuePoller(String name, Supplier<List<T>> poll, Consumer<T> handOff, Duration idleSpacing) {
    this(name, poll, handOff, idleSpacing, () -> true);
  }

  /** As above, polling only while {@code due} holds. */
  QueuePoller(
      String name,
      Supplier<List<T>> poll,
      Consumer<T> handOff,
      Duration idleSpacing,
      BooleanSupplier due) {
    this.name = name;
    this.poll = poll;
    this.handOff = handOff;
    this.idleSpacing = idleSpacing;
    this.due = due;
  }

  synchronized void start() {
    if (thread == null) {
      running = true;
      thread = Thread.ofVirtual().name("dispatcher-poll-" + name).start(this::loop);
    }
  }

  /** Stop polling; a poll in progress is abandoned and whatever it would have claimed lapses. */
  synchronized void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
  }

  /** Check at once whether the loop is due; a wake while it polls is checked after that poll. */
  void wake() {
    wakeups.release();
  }

  private void loop() {
    while (running && !Thread.currentThread().isInterrupted()) {
      if (due.getAsBoolean()) {
        pollOnce();
      } else {
        awaitWake();
      }
    }
  }

  // A wake given between the check and this wait is not lost: its permit is still there.
  private void awaitWake() {
    try {
      wakeups.tryAcquire(idleSpacing.toNanos(), TimeUnit.NANOSECONDS);
      wakeups.drainPermits();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** One poll, then the wait the pacing above asks for. */
  void pollOnce() {
    long startedNanos = System.nanoTime();
    try {
      List<T> runs = poll.get();
      if (runs != null && !runs.isEmpty()) {
        LOGGER.debug("Poll (" + name + ") claimed " + runs.size() + " run(s).");
        runs.forEach(this::handOffQuietly);
        return;
      }
    } catch (RuntimeException e) {
      // A poll cut short by stop is not a failure worth reporting.
      if (!Thread.currentThread().isInterrupted()) {
        LOGGER.warn("Poll (" + name + ") failed: " + e.getMessage());
      }
    }
    long waitNanos = startedNanos + idleSpacing.toNanos() - System.nanoTime();
    if (waitNanos > 0) {
      Backoff.sleep(Duration.ofNanos(waitNanos));
    }
  }

  // One run that cannot be handed off must not cost the rest of the poll.
  private void handOffQuietly(T run) {
    try {
      handOff.accept(run);
    } catch (RuntimeException e) {
      LOGGER.error("Poll (" + name + ") could not hand off " + run + ": " + e.getMessage(), e);
    }
  }
}
