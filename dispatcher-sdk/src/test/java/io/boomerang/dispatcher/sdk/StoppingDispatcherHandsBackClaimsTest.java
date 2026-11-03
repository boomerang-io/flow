package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskType;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A claim this dispatcher received and never started is ended failed with {@code
 * DispatcherStopping} when it stops, so the engine requeues it at once instead of when the claim's
 * lease lapses: the claims a poll in progress returns after the stop, and the ones still unstarted
 * when the drain gives up.
 */
class StoppingDispatcherHandsBackClaimsTest {

  private static final Backoff FAST = new Backoff(Duration.ofMillis(1), Duration.ofMillis(5));

  private final FakeEngine engine = new FakeEngine();
  private final CountDownLatch polling = new CountDownLatch(1);
  private final CountDownLatch answerPoll = new CountDownLatch(1);
  private Dispatcher dispatcher;

  @AfterEach
  void tearDown() {
    answerPoll.countDown();
    if (dispatcher != null) {
      dispatcher.stop();
    }
  }

  private Dispatcher.Builder builder() {
    return Dispatcher.builder()
        .client(engine)
        .name("test")
        .host("pod-1")
        .taskTypes(List.of("template"))
        .heartbeatInterval(Duration.ZERO)
        .backoff(FAST, FAST)
        .idlePollSpacing(Duration.ofMillis(20));
  }

  private static TaskRun runOrder(String id) {
    TaskRun run = new TaskRun();
    run.setId(id);
    run.setType(TaskType.template);
    run.setPhase(RunPhase.queued);
    run.setStatus(RunStatus.ready);
    return run;
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  /** An engine whose first task poll is held until the test answers it with {@code claims}. */
  private FakeEngine heldPollAnswering(List<TaskRun> claims, AtomicBoolean interrupted) {
    return new FakeEngine() {
      private final AtomicBoolean first = new AtomicBoolean(true);

      @Override
      public List<TaskRun> pollTasks(String dispatcherId, Integer limit, TaskFilter filter) {
        if (!first.compareAndSet(true, false)) {
          return List.of();
        }
        polling.countDown();
        try {
          answerPoll.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          interrupted.set(true);
          throw new IllegalStateException("poll abandoned");
        }
        return claims;
      }
    };
  }

  @Test
  void theClaimsOfAPollInProgressAtTheStopAreHandedBackNotAbandoned() throws Exception {
    AtomicBoolean interrupted = new AtomicBoolean();
    FakeEngine held = heldPollAnswering(List.of(runOrder("task-1"), runOrder("task-2")), interrupted);
    dispatcher = builder().client(held).tasks((task, context) -> TaskResult.empty()).build();
    dispatcher.start();
    assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();

    Thread stopping = Thread.ofVirtual().start(dispatcher::stop);
    Thread.sleep(200);
    assertThat(stopping.isAlive()).as("stop waits for the poll in progress").isTrue();
    answerPoll.countDown();
    stopping.join(10_000);

    assertThat(interrupted).isFalse();
    assertThat(held.starts).isEmpty();
    for (String id : List.of("task-1", "task-2")) {
      assertThat(held.endsOf(id)).singleElement().satisfies(end -> {
        assertThat(end.getStatus()).isEqualTo(RunStatus.failed);
        assertThat(end.getStatusReason()).isEqualTo("DispatcherStopping");
        assertThat(end.getDispatcherRef()).isEqualTo("d-1");
      });
    }
  }

  @Test
  void aPollStillHeldPastTheDrainIsAbandonedAndTheStopReturns() throws Exception {
    AtomicBoolean interrupted = new AtomicBoolean();
    dispatcher =
        builder()
            .client(heldPollAnswering(List.of(), interrupted))
            .drainTimeout(Duration.ofMillis(200))
            .tasks((task, context) -> TaskResult.empty())
            .build();
    dispatcher.start();
    assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();

    long started = System.nanoTime();
    dispatcher.stop();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    await(interrupted::get);
  }

  @Test
  void aClaimStillUnstartedWhenTheDrainGivesUpIsHandedBack() throws Exception {
    CountDownLatch startCalled = new CountDownLatch(1);
    AtomicBoolean ran = new AtomicBoolean();
    engine.taskPolls.add(List.of(runOrder("task-1")));
    // The engine does not answer the start: the claim is taken and unstarted at the drain.
    engine.startAnswers.add(
        () -> {
          startCalled.countDown();
          try {
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            throw new IllegalStateException("start interrupted");
          }
          return null;
        });
    dispatcher =
        builder()
            .drainTimeout(Duration.ofMillis(200))
            .tasks(
                (task, context) -> {
                  ran.set(true);
                  return TaskResult.empty();
                })
            .build();
    dispatcher.start();
    assertThat(startCalled.await(5, TimeUnit.SECONDS)).isTrue();

    dispatcher.stop();

    assertThat(ran).isFalse();
    assertThat(engine.endsOf("task-1")).singleElement().satisfies(end -> {
      assertThat(end.getStatus()).isEqualTo(RunStatus.failed);
      assertThat(end.getStatusReason()).isEqualTo("DispatcherStopping");
    });
  }

  @Test
  void aTaskThatFinishesWithinTheDrainEndsAsItDidAndIsNotHandedBack() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    dispatcher =
        builder()
            .tasks(
                (task, context) -> {
                  Thread.sleep(100);
                  return TaskResult.empty();
                })
            .build();
    dispatcher.start();
    await(() -> dispatcher.inFlight().contains("task-1"));

    dispatcher.stop();

    assertThat(engine.endsOf("task-1"))
        .extracting(TaskRunEndRequest::getStatus)
        .containsExactly(RunStatus.succeeded);
  }
}
