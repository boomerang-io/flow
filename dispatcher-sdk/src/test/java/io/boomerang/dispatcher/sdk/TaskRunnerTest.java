package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.boomerang.dispatcher.sdk.logback.TaskLogAppender;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * One task run's lifecycle: a claim to run is started, run and ended; the start answer decides
 * whether it runs; the end is retried until the engine takes it; a terminate order stops the work
 * and reports nothing. Around the handler: its logged lines as the task log and its observation,
 * each only when asked for.
 */
@Tag("smoke")
class TaskRunnerTest {

  private static final Backoff FAST = new Backoff(Duration.ofMillis(1), Duration.ofMillis(5));
  private static final org.slf4j.Logger TASK_LOG = LoggerFactory.getLogger("task");

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

  private TaskRunner runner(TaskHandler handler, Executor executor) {
    return runner(handler, executor, null, Duration.ZERO, ObservationRegistry.NOOP);
  }

  private TaskRunner runner(
      TaskHandler handler,
      Executor executor,
      TaskLogCapture capture,
      Duration logUploadInterval,
      ObservationRegistry observations) {
    Set<String> registered = Set.of("template", "custom", "script", "ai");
    return new TaskRunner(
        engine,
        () -> "d-1",
        handler,
        inFlight,
        running,
        task -> registered.contains(task.getType().name()),
        task -> null,
        new LogUploader(engine, () -> "d-1"),
        capture,
        logUploadInterval,
        observations,
        executor,
        FAST,
        abandoned::get);
  }

  /** Records every observation the runner opens, as a tracing handler would see it. */
  private static final class RecordingObservations
      implements ObservationHandler<Observation.Context> {

    final List<Observation.Context> started = new CopyOnWriteArrayList<>();
    final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    final ObservationRegistry registry = ObservationRegistry.create();

    RecordingObservations() {
      registry.observationConfig().observationHandler(this);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStart(Observation.Context context) {
      started.add(context);
    }

    @Override
    public void onStop(Observation.Context context) {
      stopped.add(context);
    }

    @SuppressWarnings("unchecked")
    static String traceparentOf(Observation.Context context) {
      return ((ReceiverContext<Map<String, String>>) context).getCarrier().get("traceparent");
    }
  }

  private String uploaded(String id) {
    return engine.logs.get(id);
  }

  private static void detach(TaskLogAppender appender) {
    ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(Logger.ROOT_LOGGER_NAME)
        .detachAppender(appender);
    appender.stop();
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
  void theLinesAHandlerLogsAreItsTaskLogUploadedInOrderBeforeTheEnd() {
    TaskLogCapture capture = new TaskLogCapture();
    TaskLogAppender appender = TaskLogAppender.attach(capture);
    AtomicReference<String> tag = new AtomicReference<>();
    AtomicBoolean uploadedBeforeEnd = new AtomicBoolean();
    engine.endAnswers.add(() -> uploadedBeforeEnd.set(engine.logs.containsKey("task-1")));
    try {
      runner(
              (task, context) -> {
                tag.set(MDC.get(TaskLogCapture.MDC_KEY));
                TASK_LOG.info("one");
                TASK_LOG.info("two {}", 2);
                TASK_LOG.warn("three");
                return TaskResult.empty();
              },
              Runnable::run,
              capture,
              Duration.ZERO,
              ObservationRegistry.NOOP)
          .accept(runOrder());
    } finally {
      detach(appender);
    }

    assertThat(tag.get()).isEqualTo("task-1");
    assertThat(uploaded("task-1")).isEqualTo("one\ntwo 2\nthree\n");
    assertThat(uploadedBeforeEnd).isTrue();
    assertThat(MDC.get(TaskLogCapture.MDC_KEY)).isNull();
    assertThat(capture.isCapturing("task-1")).isFalse();
  }

  @Test
  void aCapturedLogIsUploadedWhileTheTaskRuns() {
    TaskLogCapture capture = new TaskLogCapture();
    AtomicReference<String> midway = new AtomicReference<>();

    runner(
            (task, context) -> {
              capture.append(task.getId(), "first");
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
              while (!engine.logs.containsKey("task-1") && System.nanoTime() < deadline) {
                Thread.sleep(5);
              }
              midway.set(uploaded("task-1"));
              capture.append(task.getId(), "second");
              return TaskResult.empty();
            },
            Runnable::run,
            capture,
            Duration.ofMillis(10),
            ObservationRegistry.NOOP)
        .accept(runOrder());

    assertThat(midway.get()).isEqualTo("first\n");
    assertThat(uploaded("task-1")).isEqualTo("first\nsecond\n");
  }

  @Test
  void aPeriodicUploadInFlightWhenTheHandlerReturnsCompletesBeforeTheFinalOne() throws Exception {
    TaskLogCapture capture = new TaskLogCapture();
    // The first upload - the periodic one - is still in flight in the engine when the handler
    // returns; an interrupt would abort it there.
    CountDownLatch periodicUploading = new CountDownLatch(1);
    List<String> stored = new CopyOnWriteArrayList<>();
    engine.logAnswers.add(
        () -> {
          periodicUploading.countDown();
          try {
            Thread.sleep(300);
          } catch (InterruptedException e) {
            throw new IllegalStateException("the periodic upload was interrupted", e);
          }
          stored.add("periodic");
        });
    engine.logAnswers.add(() -> stored.add("final"));

    runner(
            (task, context) -> {
              capture.append(task.getId(), "first");
              assertThat(periodicUploading.await(10, TimeUnit.SECONDS)).isTrue();
              capture.append(task.getId(), "second");
              return TaskResult.empty();
            },
            virtualThreads,
            capture,
            Duration.ofMillis(10),
            ObservationRegistry.NOOP)
        .accept(runOrder());

    awaitEmpty();
    assertThat(stored).containsExactly("periodic", "final");
    assertThat(uploaded("task-1")).isEqualTo("first\nsecond\n");
  }

  @Test
  void aHandlerRunsUnderOneObservationCarryingTheRunsTrace() {
    RecordingObservations observations = new RecordingObservations();
    TaskRun task = runOrder();
    task.setId("6ad1bfa6cfefa66e88d5e2e0");
    task.setWorkflowRunRef("6ad1bfa3cfefa66e88d5e24a");
    task.setName("echo");
    AtomicReference<Observation> current = new AtomicReference<>();

    runner(
            (run, context) -> {
              current.set(observations.registry.getCurrentObservation());
              return TaskResult.empty();
            },
            Runnable::run,
            null,
            Duration.ZERO,
            observations.registry)
        .accept(task);

    assertThat(observations.started).hasSize(1);
    Observation.Context context = observations.started.get(0);
    assertThat(current.get()).isNotNull();
    assertThat(context.getName()).isEqualTo("flow.task");
    assertThat(RecordingObservations.traceparentOf(context))
        .isEqualTo("00-000000006ad1bfa3cfefa66e88d5e24a-cfefa66e88d5e2e0-01")
        .contains("000000006ad1bfa3cfefa66e88d5e24a");
    assertThat(context.getLowCardinalityKeyValues())
        .contains(
            KeyValue.of("flow.taskrun.id", "6ad1bfa6cfefa66e88d5e2e0"),
            KeyValue.of("flow.task.name", "echo"),
            KeyValue.of("flow.workflowrun.id", "6ad1bfa3cfefa66e88d5e24a"));
    assertThat(context.getError()).isNull();
    assertThat(observations.stopped).hasSize(1);
    assertThat(observations.registry.getCurrentObservation()).isNull();
  }

  @Test
  void aHandlerExceptionMarksTheObservationInError() {
    RecordingObservations observations = new RecordingObservations();

    runner(
            (task, context) -> {
              throw new TaskFailure("JobFailed", "exited with code 1");
            },
            Runnable::run,
            null,
            Duration.ZERO,
            observations.registry)
        .accept(runOrder());

    assertThat(observations.stopped).hasSize(1);
    assertThat(observations.stopped.get(0).getError())
        .isInstanceOf(TaskFailure.class)
        .hasMessage("exited with code 1");
    assertThat(soleEnd().getStatusReason()).isEqualTo("JobFailed");
  }

  @Test
  void aTerminalFailureIsReportedAsNotRetryable() {
    RecordingObservations observations = new RecordingObservations();

    runner(
            (task, context) -> {
              throw TaskFailure.terminal("JobFailed", "400 from the gateway");
            },
            Runnable::run,
            null,
            Duration.ZERO,
            observations.registry)
        .accept(runOrder());

    assertThat(soleEnd().getStatusReason()).isEqualTo("JobFailed");
    assertThat(soleEnd().getRetryable()).isFalse();
    assertThat(new TaskFailure("JobFailed", "exited with code 1").getRetryable()).isNull();
  }

  @Test
  void withoutTheOptionsNothingIsUploadedAndNoObservationIsOpened() {
    RecordingObservations observations = new RecordingObservations();
    AtomicReference<String> tag = new AtomicReference<>("unset");

    runner((task, context) -> {
          tag.set(MDC.get(TaskLogCapture.MDC_KEY));
          TASK_LOG.info("goes nowhere");
          return TaskResult.empty();
        })
        .accept(runOrder());

    assertThat(tag.get()).isNull();
    assertThat(engine.logs).isEmpty();
    assertThat(observations.started).isEmpty();
    assertThat(soleEnd().getStatus()).isEqualTo(RunStatus.succeeded);
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
  void aRateLimitedFailureIsReportedForTheEngineToRequeue() {
    runner((task, context) -> {
          throw TaskFailure.rateLimited("RATE_LIMITED - the gateway answered 429");
        })
        .accept(runOrder());

    TaskRunEndRequest end = soleEnd();
    assertThat(end.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(end.getStatusReason()).isEqualTo("RateLimited");
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
  void aTerminateOrderArrivingDuringTheStartCallStopsTheTaskBeforeItRuns() throws Exception {
    // The engine answers the start only after the terminate order has been delivered.
    CountDownLatch starting = new CountDownLatch(1);
    CountDownLatch terminated = new CountDownLatch(1);
    engine.startAnswers.add(
        () -> {
          starting.countDown();
          try {
            terminated.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return answer(RunPhase.running, RunStatus.running);
        });
    AtomicBoolean ran = new AtomicBoolean();
    TaskRunner runner =
        runner(
            (task, context) -> {
              ran.set(true);
              return TaskResult.empty();
            },
            virtualThreads);

    runner.accept(runOrder());
    assertThat(starting.await(10, TimeUnit.SECONDS)).isTrue();
    runner.accept(taskRun(TaskType.template, RunPhase.completed, RunStatus.cancelled));
    terminated.countDown();

    awaitEmpty();
    assertThat(ran).isFalse();
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
              throw new RejectedExecutionException("stopping");
            })
        .accept(runOrder());

    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aClaimArrivingAfterTheStopIsRefusedBeforeItIsTaken() {
    AtomicBoolean handedOff = new AtomicBoolean();
    TaskRunner runner =
        runner((task, context) -> TaskResult.empty(), work -> handedOff.set(true));
    runner.stop();

    runner.accept(runOrder());

    assertThat(handedOff).isFalse();
    assertThat(inFlight.size()).isZero();
    assertThat(engine.starts).isEmpty();
  }

  // The stop lands in the gap between the claim entering flight and its hand-off: the claim is
  // registered here, so it must reach its end rather than be dropped as not taken.
  @Test
  void aStopLandingBetweenRegistrationAndTheHandOffStillDrainsTheClaim() throws Exception {
    DirectExecutor executor = new DirectExecutor();
    AtomicReference<Thread> stopper = new AtomicReference<>();
    TaskRunner runner = runner((task, context) -> TaskResult.empty(), executor);
    inFlight.onChange(
        () -> {
          Thread stop = Thread.ofVirtual().unstarted(() -> stop(runner, executor));
          if (stopper.compareAndSet(null, stop)) {
            stop.start();
            try {
              // A stop that cannot land before the hand-off leaves the accept to carry on.
              stopper.get().join(300);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });

    runner.accept(runOrder());
    stopper.get().join(5000);

    assertThat(engine.endsOf("task-1")).hasSize(1);
    assertThat(inFlight.size()).isZero();
  }

  @Test
  void aClaimRacedAgainstTheStopIsDrainedOrRefusedBeforeItIsTaken() throws Exception {
    AtomicBoolean registered = new AtomicBoolean();
    inFlight.onChange(() -> registered.set(true));
    for (int i = 0; i < 300; i++) {
      String id = "task-" + i;
      TaskRun claim = runOrder();
      claim.setId(id);
      DirectExecutor executor = new DirectExecutor();
      TaskRunner runner = runner((task, context) -> TaskResult.empty(), executor);
      registered.set(false);
      CountDownLatch go = new CountDownLatch(1);
      Thread accepting = Thread.ofVirtual().start(() -> {
            await(go);
            runner.accept(claim);
          });
      Thread stopping = Thread.ofVirtual().start(() -> {
            await(go);
            stop(runner, executor);
          });

      go.countDown();
      accepting.join(5000);
      stopping.join(5000);

      assertThat(inFlight.size()).as(id).isZero();
      if (registered.get()) {
        assertThat(engine.endsOf(id)).as(id + " registered").hasSize(1);
      } else {
        assertThat(engine.starts).as(id + " refused").doesNotContain(id + ":d-1");
      }
    }
  }

  // What Dispatcher.stop does to a runner and the executor it shares.
  private static void stop(TaskRunner runner, DirectExecutor executor) {
    runner.stop();
    executor.shutdown();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Runs the work on the calling thread, and rejects it once shut down, as the real one does. */
  private static final class DirectExecutor implements Executor {

    private volatile boolean shutDown;

    @Override
    public void execute(Runnable work) {
      if (shutDown) {
        throw new RejectedExecutionException("stopping");
      }
      work.run();
    }

    void shutdown() {
      shutDown = true;
    }
  }

  private void awaitEmpty() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (inFlight.size() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(inFlight.size()).isZero();
  }
}
