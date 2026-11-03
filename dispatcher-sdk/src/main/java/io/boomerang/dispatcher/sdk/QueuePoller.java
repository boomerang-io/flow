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
  private boolean polling;
  private volatile boolean stopRequested;

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

  /**
   * Stop polling once the poll in progress, if any, has returned and its runs are handed off: the
   * engine may already have claimed for it, and a request abandoned mid-flight would leave those
   * claims to lapse. A loop that is waiting rather than polling is woken at once. Call {@link
   * #awaitStopped} to wait for the end, and {@link #abandon} to give up on a poll that outlasts it.
   */
  synchronized void stop() {
    running = false;
    stopRequested = true;
    if (thread != null && !polling) {
      thread.interrupt();
    }
  }

  /** Wait up to {@code timeout} for the loop to end; false when it is still running. */
  boolean awaitStopped(Duration timeout) {
    Thread loop = thread;
    if (loop == null) {
      return true;
    }
    try {
      return loop.join(timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return !loop.isAlive();
    }
  }

  /** Interrupt a poll in progress; whatever the engine claims for it from here on lapses. */
  synchronized void abandon() {
    running = false;
    stopRequested = true;
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
      if (isDue()) {
        pollOnce();
      } else {
        awaitWake();
      }
    }
  }

  // The due check runs the caller's own code (a pool's free-slot count), so a throw there is
  // logged and read as not due; the poller waits its spacing and asks again, and never ends.
  private boolean isDue() {
    try {
      return due.getAsBoolean();
    } catch (RuntimeException e) {
      LOGGER.warn("Poll (" + name + ") could not tell whether it is due: " + e.getMessage());
      return false;
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
      List<T> runs = request();
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
    if (!stopRequested && waitNanos > 0) {
      Backoff.sleep(Duration.ofNanos(waitNanos));
    }
  }

  // The request runs uninterrupted by stop(), which waits for it instead.
  private List<T> request() {
    synchronized (this) {
      if (stopRequested) {
        return null;
      }
      polling = true;
    }
    try {
      return poll.get();
    } finally {
      synchronized (this) {
        polling = false;
      }
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
