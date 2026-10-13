package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * One task run's lifecycle: a claim to run is started, run and ended; the start answer decides
 * whether it runs; the end is retried until the engine takes it; a terminate order stops the work
 * and reports nothing.
 */
class TaskRunnerTest {

  private static final Backoff FAST = new Backoff(Duration.ofMillis(1), Duration.ofMillis(5));

  private final FakeEngine engine = new FakeEngine();
  private final InFlightTasks inFlight = new InFlightTasks(() -> 25);
  private final Map<String, TaskContext> running = new ConcurrentHashMap<>();
  private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean abandoned = new AtomicBoolean();

  @AfterEach
  void tearDown() {
    virtualThreads.shutdownNow();
  }

  private TaskRunner runner(TaskHandler handler) {
    return runner(handler, Runnable::run);
  }

  private TaskRunner runner(TaskHandler handler, java.util.concurrent.Executor executor) {
    Set<String> registered = Set.of("template", "custom", "script", "ai");
    return new TaskRunner(
        engine,
        () -> "d-1",
        handler,
        inFlight,
        running,
        task -> registered.contains(task.getType().name()),
        task -> null,
        executor,
        FAST,
        abandoned::get);
  }

  private static TaskRun taskRun(TaskType type, RunPhase phase, RunStatus status) {
    TaskRun run = new TaskRun();
    run.setId("task-1");
    run.setType(type);
    run.setPhase(phase);
    run.setStatus(status);
    return run;
  }

  private static TaskRun runOrder() {
    return taskRun(TaskType.template, RunPhase.queued, RunStatus.ready);
  }

  private static TaskRun answer(RunPhase phase, RunStatus status) {
    return taskRun(TaskType.template, phase, status);
  }

  private TaskRunEndRequest soleEnd() {
    assertThat(engine.endsOf("task-1")).hasSize(1);
    return engine.endsOf("task-1").get(0);
  }

  @Test
  void aRunOrderIsStartedRunAndEndedSucceededWithItsResults() {
    List<RunResult> results = List.of(new RunResult("greeting", "hello"));

    runner((task, context) -> TaskResult.of(results, "done")).accept(runOrder());

    assertThat(engine.starts).containsExactly("task-1:d-1");
    TaskRunEndRequest end = soleEnd();
    assertThat(end.getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(end.getResults()).isEqualTo(results);
    assertThat(end.getStatusMessage()).isEqualTo("done");
    assertThat(end.getDispatcherRef()).isEqualTo("d-1");
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void anAiTaskRunsLikeAnyOtherRegisteredType() {
    runner((task, context) -> TaskResult.empty())
        .accept(taskRun(TaskType.ai, RunPhase.queued, RunStatus.ready));

    assertThat(soleEnd().getStatus()).isEqualTo(RunStatus.succeeded);
  }

  @Test
  void aTaskTheEngineSaysIsFinishedIsNeverRun() {
    // Cancelled while it was being handed over: running it now would do work nobody waits for.
    engine.startAnswers.add(() -> answer(RunPhase.completed, RunStatus.cancelled));
    AtomicBoolean ran = new AtomicBoolean();

    runner((task, context) -> {
          ran.set(true);
          return TaskResult.empty();
        })
        .accept(runOrder());

    assertThat(ran).isFalse();
    assertThat(engine.endsOf("task-1")).isEmpty();
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aStartTheEngineRefusesIsNeverRun() {
    engine.startAnswers.add(
        () -> {
          throw FakeEngine.refused(HttpStatus.CONFLICT);
        });
    AtomicBoolean ran = new AtomicBoolean();

    runner((task, context) -> {
          ran.set(true);
          return TaskResult.empty();
        })
        .accept(runOrder());

    assertThat(ran).isFalse();
    assertThat(engine.endsOf("task-1")).isEmpty();
  }

  @Test
  void anAcceptedStartGoesAhead() {
    engine.startAnswers.add(() -> answer(RunPhase.queued, RunStatus.ready));

    runner((task, context) -> TaskResult.empty()).accept(runOrder());

    assertThat(soleEnd().getStatus()).isEqualTo(RunStatus.succeeded);
  }

  @Test
  void anUnreachableEngineStillLetsTheTaskGoAhead() {
    engine.startAnswers.add(
        () -> {
          throw FakeEngine.unreachable();
        });

    runner((task, context) -> TaskResult.empty()).accept(runOrder());

    assertThat(soleEnd().getStatus()).isEqualTo(RunStatus.succeeded);
  }

  @Test
  void aTypedFailureCarriesItsReasonAndTheResultsWrittenBeforeIt() {
    // A task can write its results and still exit non-zero; its output still reaches the engine.
    List<RunResult> results = List.of(new RunResult("statusCode", "404"));

    runner((task, context) -> {
          throw new TaskFailure("JobFailed", "exited with code 1", results);
        })
        .accept(runOrder());

    TaskRunEndRequest end = soleEnd();
    assertThat(end.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(end.getStatusReason()).isEqualTo("JobFailed");
    assertThat(end.getStatusMessage()).isEqualTo("exited with code 1");
    assertThat(end.getResults()).isEqualTo(results);
  }

  @Test
  void aNeverStartedFailureIsReportedForTheEngineToRequeue() {
    runner((task, context) -> {
          throw TaskFailure.exceededQuota("EXCEEDED_QUOTA - jobs.batch is forbidden");
        })
        .accept(runOrder());

    assertThat(soleEnd().getStatusReason()).isEqualTo("ExceededQuota");
  }

  @Test
  void anyOtherExceptionEndsTheTaskAsADispatchError() {
    runner((task, context) -> {
          throw new IllegalStateException("boom");
        })
        .accept(runOrder());

    TaskRunEndRequest end = soleEnd();
    assertThat(end.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(end.getStatusReason()).isEqualTo("DispatchError");
    assertThat(end.getStatusMessage()).isEqualTo("boom");
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void anEndTheEngineDidNotTakeIsRetriedWhileTheTaskStaysInFlight() {
    // A dropped end lets the lease lapse and the engine run a finished task again.
    AtomicBoolean inFlightWhileRetrying = new AtomicBoolean();
    engine.endAnswers.add(
        () -> {
          throw FakeEngine.unreachable();
        });
    engine.endAnswers.add(
        () -> {
          inFlightWhileRetrying.set(inFlight.contains("task-1"));
          throw FakeEngine.failing();
        });

    runner((task, context) -> TaskResult.empty()).accept(runOrder());

    assertThat(engine.endsOf("task-1")).hasSize(3);
    assertThat(inFlightWhileRetrying).isTrue();
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void anEndTheEngineRefusesIsNotRetried() {
    // 409: this dispatcher no longer holds the claim, so nothing it sends will be taken.
    engine.endAnswers.add(
        () -> {
          throw FakeEngine.refused(HttpStatus.CONFLICT);
        });

    runner((task, context) -> TaskResult.empty()).accept(runOrder());

    assertThat(engine.endsOf("task-1")).hasSize(1);
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void anEndAskedToSlowDownIsRetried() {
    engine.endAnswers.add(
        () -> {
          throw FakeEngine.refused(HttpStatus.TOO_MANY_REQUESTS);
        });

    runner((task, context) -> TaskResult.empty()).accept(runOrder());

    assertThat(engine.endsOf("task-1")).hasSize(2);
  }

  @Test
  void anEndStillUndeliveredWhenTheDispatcherStopsIsGivenUp() throws Exception {
    for (int i = 0; i < 1000; i++) {
      engine.endAnswers.add(
          () -> {
            throw FakeEngine.unreachable();
          });
    }
    Thread thread =
        Thread.ofVirtual()
            .start(() -> runner((task, context) -> TaskResult.empty()).accept(runOrder()));
    Thread.sleep(50);

    thread.interrupt();
    thread.join(5000);

    assertThat(thread.isAlive()).isFalse();
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aTaskInterruptedByAStopPastItsDrainReportsNothing() {
    // The work may still finish where it runs; the engine hands the task out again instead.
    runner((task, context) -> {
          abandoned.set(true);
          throw new InterruptedException("sleep interrupted");
        })
        .accept(runOrder());

    assertThat(engine.ends).isEmpty();
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aTerminateOrderSignalsTheRunningTaskAndReportsNoEnd() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<TaskRun> cancelled = new AtomicReference<>();
    TaskRunner runner =
        runner(
            new TaskHandler() {
              @Override
              public TaskResult run(TaskRun task, TaskContext context) throws Exception {
                CountDownLatch stopped = new CountDownLatch(1);
                context.onCancel(stopped::countDown);
                started.countDown();
                stopped.await(10, TimeUnit.SECONDS);
                return TaskResult.empty();
              }

              @Override
              public void cancel(TaskRun task) {
                cancelled.set(task);
              }
            },
            virtualThreads);

    runner.accept(runOrder());
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    runner.accept(taskRun(TaskType.template, RunPhase.completed, RunStatus.cancelled));

    awaitEmpty();
    assertThat(cancelled.get()).isNotNull();
    assertThat(engine.endsOf("task-1")).isEmpty();
  }

  @Test
  void aTerminateOrderForWorkNotRunningHereStillReachesTheHandler() {
    AtomicReference<TaskRun> cancelled = new AtomicReference<>();
    TaskRunner runner =
        runner(
            new TaskHandler() {
              @Override
              public TaskResult run(TaskRun task, TaskContext context) {
                return TaskResult.empty();
              }

              @Override
              public void cancel(TaskRun task) {
                cancelled.set(task);
              }
            });

    runner.accept(taskRun(TaskType.ai, RunPhase.completed, RunStatus.timedout));

    assertThat(cancelled.get()).isNotNull();
    assertThat(engine.starts).isEmpty();
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aFailedTerminationReportsNoEnd() {
    // Nothing left to cancel is the ordinary case after a start failure.
    TaskRunner runner =
        runner(
            new TaskHandler() {
              @Override
              public TaskResult run(TaskRun task, TaskContext context) {
                return TaskResult.empty();
              }

              @Override
              public void cancel(TaskRun task) {
                throw new IllegalStateException("No jobs found");
              }
            });

    runner.accept(taskRun(TaskType.template, RunPhase.completed, RunStatus.timedout));

    assertThat(engine.ends).isEmpty();
  }

  @Test
  void aTypeThisDispatcherDidNotRegisterIsSkipped() {
    // approval is decided inside the engine; a dispatcher skips it rather than fail it.
    runner((task, context) -> TaskResult.empty())
        .accept(taskRun(TaskType.approval, RunPhase.queued, RunStatus.ready));

    assertThat(engine.starts).isEmpty();
  }

  @Test
  void aPendingTaskIsNotAnOrder() {
    runner((task, context) -> TaskResult.empty())
        .accept(taskRun(TaskType.template, RunPhase.pending, RunStatus.ready));

    assertThat(engine.starts).isEmpty();
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aRunOrderIsInFlightBeforeTheHandOffReturns() {
    // The next poll starts at once, so it must already count the task handed off.
    runner((task, context) -> TaskResult.empty(), work -> {}).accept(runOrder());

    assertThat(inFlight.ids()).containsExactly("task-1");
    assertThat(inFlight.free()).isEqualTo(24);
  }

  @Test
  void aTerminateOrderTakesNoCapacity() {
    runner((task, context) -> TaskResult.empty(), work -> {})
        .accept(taskRun(TaskType.template, RunPhase.completed, RunStatus.cancelled));

    assertThat(inFlight.free()).isEqualTo(25);
  }

  @Test
  void aSecondClaimForATaskAlreadyInFlightIsIgnored() {
    TaskRunner runner = runner((task, context) -> TaskResult.empty(), work -> {});

    runner.accept(runOrder());
    runner.accept(runOrder());

    assertThat(inFlight.size()).isEqualTo(1);
  }

  @Test
  void aClaimArrivingWhileTheDispatcherStopsIsLetGo() {
    runner(
            (task, context) -> TaskResult.empty(),
            work -> {
              throw new java.util.concurrent.RejectedExecutionException("stopping");
            })
        .accept(runOrder());

    assertThat(inFlight.size()).isZero();
  }

  private void awaitEmpty() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (inFlight.size() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(inFlight.size()).isZero();
  }
}
