package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The runtime: registration that waits for the engine, one poll per pool asking for its free
 * capacity, the workflow queue only for a dispatcher that provisions storage, one batched heartbeat
 * for every task in flight, and a stop that drains.
 */
class DispatcherTest {

  private static final Backoff FAST = new Backoff(Duration.ofMillis(1), Duration.ofMillis(5));

  private final FakeEngine engine = new FakeEngine();
  private final CountDownLatch release = new CountDownLatch(1);
  private Dispatcher dispatcher;

  @AfterEach
  void tearDown() {
    release.countDown();
    if (dispatcher != null) {
      dispatcher.stop();
    }
  }

  private Dispatcher.Builder builder() {
    return Dispatcher.builder()
        .client(engine)
        .name("test")
        .host("pod-1")
        .taskTypes(List.of("template", "ai"))
        .heartbeatInterval(Duration.ZERO)
        .backoff(FAST, FAST)
        .idlePollSpacing(Duration.ofMillis(20));
  }

  // A handler that holds its task until the test releases it.
  private TaskHandler holding() {
    return (task, context) -> {
      release.await(10, TimeUnit.SECONDS);
      return TaskResult.empty();
    };
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

  @Test
  void registrationIsRetriedUntilTheEngineAnswers() throws Exception {
    engine.registerAnswers.add(
        () -> {
          throw FakeEngine.unreachable();
        });
    engine.registerAnswers.add(
        () -> {
          throw FakeEngine.failing();
        });
    engine.registerAnswers.add(
        () -> {
          throw FakeEngine.refused(HttpStatus.UNAUTHORIZED);
        });
    engine.registerAnswers.add(() -> "d-9");
    dispatcher = builder().tasks(holding()).build();

    dispatcher.start();

    await(dispatcher::isRegistered);
    assertThat(dispatcher.id()).isEqualTo("d-9");
    assertThat(engine.registrations).hasSize(4);
  }

  @Test
  void nothingIsPolledBeforeRegistration() throws Exception {
    CountDownLatch registering = new CountDownLatch(1);
    engine.registerAnswers.add(
        () -> {
          try {
            registering.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "d-1";
        });
    dispatcher = builder().tasks(holding()).build();

    dispatcher.start();
    Thread.sleep(100);
    assertThat(engine.taskPollLimits).isEmpty();

    registering.countDown();
    await(() -> !engine.taskPollLimits.isEmpty());
  }

  @Test
  void eachTaskPollAsksForThePoolsFreeCapacityWithItsFilter() throws Exception {
    TaskFilter aiOnly = TaskFilter.none().types("ai");
    engine.taskPolls.add(List.of());
    engine.taskPolls.add(List.of(runOrder("task-1"), runOrder("task-2")));
    dispatcher = builder().taskPool("ai", aiOnly, () -> 5, holding()).build();

    dispatcher.start();

    await(() -> engine.taskPollLimits.size() >= 3);
    assertThat(engine.taskPollLimits.get(0)).isEqualTo(5);
    assertThat(engine.taskPollLimits.get(2)).isEqualTo(3);
    assertThat(engine.taskPollFilters).allMatch(filter -> filter == aiOnly);
    assertThat(dispatcher.inFlight()).containsExactlyInAnyOrder("task-1", "task-2");
  }

  @Test
  void aPoolWithNoCapSendsNoLimit() throws Exception {
    dispatcher = builder().tasks(holding()).build();

    dispatcher.start();

    await(() -> !engine.taskPollLimits.isEmpty());
    assertThat(engine.taskPollLimits.get(0)).isNull();
  }

  @Test
  void theWorkflowQueueIsPolledOnlyByADispatcherThatProvisions() throws Exception {
    dispatcher = builder().tasks(holding()).build();
    dispatcher.start();
    await(() -> engine.taskPollLimits.size() >= 3);
    assertThat(engine.workflowPolls).isEmpty();
    dispatcher.stop();

    WorkflowRun run = new WorkflowRun();
    run.setId("wf-1");
    run.setPhase(RunPhase.queued);
    run.setStatus(RunStatus.ready);
    engine.workflowPollAnswers.add(List.of(run));
    dispatcher = builder().tasks(holding()).workflows(workflowRun -> {}).build();
    dispatcher.start();

    await(() -> engine.workflowStarts.contains("wf-1"));
  }

  @Test
  void aDispatcherWithNothingInFlightSendsNoHeartbeat() throws Exception {
    dispatcher = builder().tasks(holding()).build();
    dispatcher.start();
    await(dispatcher::isRegistered);

    dispatcher.beat();

    assertThat(engine.heartbeats).isEmpty();
  }

  @Test
  void everyTaskInFlightIsRenewedInOneBatch() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1"), runOrder("task-2")));
    dispatcher = builder().tasks(holding()).build();
    dispatcher.start();
    await(() -> dispatcher.inFlight().size() == 2);

    dispatcher.beat();

    assertThat(engine.heartbeats).hasSize(1);
    assertThat(engine.heartbeats.get(0)).containsExactlyInAnyOrder("task-1", "task-2");
  }

  @Test
  void theHeartbeatRunsOnItsInterval() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    dispatcher = builder().heartbeatInterval(Duration.ofMillis(20)).tasks(holding()).build();

    dispatcher.start();

    await(() -> engine.heartbeats.size() >= 2);
    assertThat(engine.heartbeats.get(0)).containsExactly("task-1");
  }

  @Test
  void stopWaitsForTheTasksInFlightToReport() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    dispatcher =
        builder()
            .drainTimeout(Duration.ofSeconds(10))
            .tasks(
                (task, context) -> {
                  Thread.sleep(200);
                  return TaskResult.empty();
                })
            .build();
    dispatcher.start();
    await(() -> dispatcher.inFlight().contains("task-1"));

    dispatcher.stop();

    assertThat(engine.endsOf("task-1")).singleElement().extracting("status")
        .isEqualTo(RunStatus.succeeded);
  }

  @Test
  void aTaskStillRunningPastTheDrainIsLeftToTheEngine() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    dispatcher = builder().drainTimeout(Duration.ofMillis(100)).tasks(holding()).build();
    dispatcher.start();
    await(() -> dispatcher.inFlight().contains("task-1"));

    long started = System.nanoTime();
    dispatcher.stop();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    await(() -> dispatcher.inFlight().isEmpty());
    assertThat(engine.endsOf("task-1")).isEmpty();
  }

  @Test
  void aDispatcherNeedsANameAndSomethingToRun() {
    assertThatThrownBy(() -> Dispatcher.builder().client(engine).tasks(holding()).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Dispatcher.builder().client(engine).name("test").build())
        .isInstanceOf(IllegalStateException.class);
  }
}
