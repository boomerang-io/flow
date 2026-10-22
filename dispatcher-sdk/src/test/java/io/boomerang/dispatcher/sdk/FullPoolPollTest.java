package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A full pool keeps a poll that claims nothing open for terminate orders, and a slot that frees
 * while the engine holds that poll is filled at once by a work poll alongside it - never by more
 * than the free slots, and never by two work polls at a time.
 */
class FullPoolPollTest {

  // The engine's hold on a poll it has nothing for; the pacing matches it, so only the wake on a
  // freed slot can explain a prompt claim.
  private static final Duration HOLD = Duration.ofSeconds(5);
  private static final Backoff FAST = new Backoff(Duration.ofMillis(1), Duration.ofMillis(5));

  private final HoldingEngine engine = new HoldingEngine();
  private final Map<String, CountDownLatch> releases = new ConcurrentHashMap<>();
  private final List<String> cancelled = new CopyOnWriteArrayList<>();
  private Dispatcher dispatcher;

  @AfterEach
  void tearDown() {
    // A slot freed here must find nothing left to claim, or stop waits out its drain.
    engine.taskPolls.clear();
    releases.values().forEach(CountDownLatch::countDown);
    if (dispatcher != null) {
      dispatcher.stop();
    }
  }

  private Dispatcher start(int cap) {
    dispatcher =
        Dispatcher.builder()
            .client(engine)
            .name("test")
            .host("pod-1")
            .taskTypes(List.of("template"))
            .heartbeatInterval(Duration.ZERO)
            .backoff(FAST, FAST)
            .idlePollSpacing(HOLD)
            .tasks(holdingEach(), () -> cap)
            .build();
    engine.cap = cap;
    engine.dispatcher = dispatcher;
    dispatcher.start();
    return dispatcher;
  }

  // Each task holds until the test releases it by id.
  private TaskHandler holdingEach() {
    return new TaskHandler() {
      @Override
      public TaskResult run(TaskRun task, TaskContext context) throws Exception {
        release(task.getId()).await(10, TimeUnit.SECONDS);
        return TaskResult.empty();
      }

      @Override
      public void cancel(TaskRun task) {
        cancelled.add(task.getId());
      }
    };
  }

  private CountDownLatch release(String taskRunId) {
    return releases.computeIfAbsent(taskRunId, id -> new CountDownLatch(1));
  }

  private static TaskRun runOrder(String id) {
    TaskRun run = new TaskRun();
    run.setId(id);
    run.setType(TaskType.template);
    run.setPhase(RunPhase.queued);
    run.setStatus(RunStatus.ready);
    return run;
  }

  private static TaskRun terminateOrder(String id) {
    TaskRun run = runOrder(id);
    run.setPhase(RunPhase.completed);
    run.setStatus(RunStatus.cancelled);
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
  void aSlotThatFreesWhileTheFullPoolPollIsHeldIsFilledAtOnce() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    start(1);
    await(() -> engine.zeroPollsHeld.get() == 1);

    engine.taskPolls.add(List.of(runOrder("task-2")));
    long freed = System.nanoTime();
    release("task-1").countDown();
    await(() -> dispatcher.inFlight().contains("task-2"));

    assertThat(Duration.ofNanos(System.nanoTime() - freed)).isLessThan(Duration.ofSeconds(1));
    assertThat(engine.workPollWhileZeroPollHeld).isTrue();
    assertThat(engine.endsOf("task-1")).singleElement().extracting("status")
        .isEqualTo(RunStatus.succeeded);
  }

  @Test
  void twoSlotsFreeingAtOnceAreFilledWithoutPassingTheCap() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1"), runOrder("task-2")));
    start(2);
    await(() -> engine.zeroPollsHeld.get() == 1);

    engine.taskPolls.add(List.of(runOrder("task-3")));
    engine.taskPolls.add(List.of(runOrder("task-4")));
    engine.taskPolls.add(List.of(runOrder("task-5")));
    release("task-1").countDown();
    release("task-2").countDown();
    await(() -> dispatcher.inFlight().containsAll(List.of("task-3", "task-4")));
    Thread.sleep(200);

    assertThat(dispatcher.inFlight()).containsExactlyInAnyOrder("task-3", "task-4");
    assertThat(engine.overCap).isEmpty();
    assertThat(engine.mostWorkPollsAtOnce.get()).isEqualTo(1);
  }

  @Test
  void terminateOrdersStillArriveWhileThePoolIsFull() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    engine.zeroTaskPolls.add(List.of(terminateOrder("task-1")));
    start(1);

    await(() -> cancelled.contains("task-1"));

    assertThat(engine.taskPollLimits).contains(0);
    assertThat(dispatcher.inFlight()).containsExactly("task-1");
  }

  @Test
  void aPoolThatIsNotFullSendsNoZeroTaskPoll() throws Exception {
    engine.taskPolls.add(List.of(runOrder("task-1")));
    start(2);
    await(() -> dispatcher.inFlight().contains("task-1"));
    Thread.sleep(200);

    assertThat(engine.taskPollLimits).doesNotContain(0);
  }

  /**
   * Answers a poll that claims nothing from its own script, holding it for {@link #HOLD} when the
   * script is empty, as the engine does; checks every work poll against the cap.
   */
  static final class HoldingEngine extends FakeEngine {

    final Queue<List<TaskRun>> zeroTaskPolls = new ConcurrentLinkedQueue<>();
    final AtomicInteger zeroPollsHeld = new AtomicInteger();
    final AtomicInteger workPollsInFlight = new AtomicInteger();
    final AtomicInteger mostWorkPollsAtOnce = new AtomicInteger();
    final List<String> overCap = new CopyOnWriteArrayList<>();
    volatile boolean workPollWhileZeroPollHeld;
    volatile int cap;
    volatile Dispatcher dispatcher;

    @Override
    public List<TaskRun> pollTasks(String dispatcherId, Integer limit, TaskFilter filter) {
      if (limit != null && limit == 0) {
        taskPollLimits.add(0);
        List<TaskRun> answer = zeroTaskPolls.poll();
        if (answer != null) {
          return answer;
        }
        zeroPollsHeld.incrementAndGet();
        try {
          return hold();
        } finally {
          zeroPollsHeld.decrementAndGet();
        }
      }
      mostWorkPollsAtOnce.accumulateAndGet(workPollsInFlight.incrementAndGet(), Math::max);
      try {
        workPollWhileZeroPollHeld |= zeroPollsHeld.get() > 0;
        int inFlight = dispatcher.inFlight().size();
        if (limit == null || inFlight + limit > cap) {
          overCap.add(inFlight + " in flight, limit " + limit);
        }
        List<TaskRun> answer = super.pollTasks(dispatcherId, limit, filter);
        return answer.isEmpty() ? hold() : answer;
      } finally {
        workPollsInFlight.decrementAndGet();
      }
    }

    private static List<TaskRun> hold() {
      Backoff.sleep(HOLD);
      return List.of();
    }
  }
}
