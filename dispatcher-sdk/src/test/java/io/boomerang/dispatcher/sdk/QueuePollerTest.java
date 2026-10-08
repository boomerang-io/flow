package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Poll pacing: a poll that brought runs, or one the engine held for its window, is followed by the
 * next at once; a poll the engine answered at once with nothing, or that failed, waits so the
 * engine is not asked in a tight loop.
 */
class QueuePollerTest {

  // Shorter than the shipped 5 s so the suite stays fast; the rule is the same.
  private static final Duration SPACING = Duration.ofMillis(800);

  private final List<String> handedOff = new CopyOnWriteArrayList<>();

  private QueuePoller<String> poller(Supplier<List<String>> poll) {
    return new QueuePoller<>("test", poll, handedOff::add, SPACING);
  }

  private static long millisToPoll(QueuePoller<String> poller) {
    long started = System.nanoTime();
    poller.pollOnce();
    return Duration.ofNanos(System.nanoTime() - started).toMillis();
  }

  @Test
  void theShippedSpacingIsFiveSeconds() {
    assertThat(QueuePoller.IDLE_POLL_SPACING).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void aPollThatBroughtRunsReturnsAtOnceAndHandsEachOff() {
    assertThat(millisToPoll(poller(() -> List.of("task-1", "task-2"))))
        .isLessThan(SPACING.toMillis() / 2);
    assertThat(handedOff).containsExactly("task-1", "task-2");
  }

  @Test
  void aPollTheEngineHeldForItsWindowReturnsAtOnce() {
    QueuePoller<String> held =
        poller(
            () -> {
              Backoff.sleep(SPACING);
              return List.of();
            });

    assertThat(millisToPoll(held)).isLessThan(SPACING.toMillis() + 400);
  }

  @Test
  void anEmptyAnswerGivenAtOnceWaitsBeforeTheNextPoll() {
    assertThat(millisToPoll(poller(List::of))).isGreaterThanOrEqualTo(SPACING.toMillis() - 50);
  }

  @Test
  void aFailedPollWaitsBeforeTheNextPoll() {
    QueuePoller<String> failing =
        poller(
            () -> {
              throw FakeEngine.unreachable();
            });

    assertThat(millisToPoll(failing)).isGreaterThanOrEqualTo(SPACING.toMillis() - 50);
  }

  @Test
  void aRunThatCannotBeHandedOffDoesNotCostTheRest() {
    QueuePoller<String> poller =
        new QueuePoller<>(
            "test",
            () -> List.of("bad", "good"),
            run -> {
              if (run.equals("bad")) {
                throw new IllegalStateException("bad run");
              }
              handedOff.add(run);
            },
            SPACING);

    poller.pollOnce();

    assertThat(handedOff).containsExactly("good");
  }

  @Test
  void aStartedPollerPollsUntilStopped() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    QueuePoller<String> poller =
        poller(
            () -> {
              polls.incrementAndGet();
              return List.of("task");
            });

    poller.start();
    Thread.sleep(100);
    poller.stop();
    int stoppedAt = polls.get();
    Thread.sleep(100);

    assertThat(stoppedAt).isGreaterThan(1);
    assertThat(polls.get()).isLessThanOrEqualTo(stoppedAt + 1);
  }
}
