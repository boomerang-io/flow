package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.TaskType;
import io.boomerang.dispatcher.PollWakeups.TaskWaiter;
import io.boomerang.engine.model.ClaimFilter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A task poll's wait ends at an admission its filter can claim - by type, and by task and
 * workflow when it filtered on them - at the interval, or at an interrupt; an admission another
 * filter would claim leaves it waiting, and one that landed after the poll registered and before
 * it waited ends the wait at once. A run signal reaches the workflow waits alone.
 */
class PollWakeupsTest {

  private static final String WORKFLOW = "wf-1";
  private static final String TASK = "task-1";

  private final PollWakeups wakeups = new PollWakeups();

  private static ClaimFilter templates() {
    return ClaimFilter.of(List.of(TaskType.template));
  }

  // Wait up to five seconds on the waiter from another thread and report how long it waited.
  private Future<Long> waitInBackground(ExecutorService executor, TaskWaiter waiter) {
    return executor.submit(
        () -> {
          long started = System.nanoTime();
          wakeups.awaitMatch(waiter, Duration.ofSeconds(5));
          return Duration.ofNanos(System.nanoTime() - started).toMillis();
        });
  }

  @Test
  void aMatchingAdmissionEndsTheWaitBeforeTheInterval() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    TaskWaiter waiter = wakeups.register(templates());
    try {
      Future<Long> waited = waitInBackground(executor, waiter);
      Thread.sleep(100);
      wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
      long waitedMs = waited.get(1, TimeUnit.SECONDS);
      assertTrue(waitedMs < 1000, "woke " + waitedMs + " ms after the wait began");
    } finally {
      wakeups.unregister(waiter);
      executor.shutdownNow();
    }
  }

  @Test
  void anAdmissionOfAnotherTypeLeavesTheWaitRunning() throws Exception {
    TaskWaiter waiter = wakeups.register(templates());
    try {
      long started = System.nanoTime();
      wakeups.taskAdmitted(TaskType.custom, TASK, WORKFLOW);
      wakeups.awaitMatch(waiter, Duration.ofMillis(200));
      long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs >= 190, "waited only " + waitedMs + " ms after a custom admission");
    } finally {
      wakeups.unregister(waiter);
    }
  }

  @Test
  void aTaskOrWorkflowFilterIsHonoured() throws Exception {
    TaskWaiter onTask =
        wakeups.register(new ClaimFilter(List.of(TaskType.template), List.of(TASK), null));
    TaskWaiter onWorkflow =
        wakeups.register(new ClaimFilter(List.of(TaskType.template), null, List.of(WORKFLOW)));
    try {
      wakeups.taskAdmitted(TaskType.template, "other-task", "other-wf");
      long started = System.nanoTime();
      wakeups.awaitMatch(onTask, Duration.ofMillis(150));
      wakeups.awaitMatch(onWorkflow, Duration.ofMillis(150));
      long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs >= 290, "waited only " + waitedMs + " ms: a filtered poll was woken");

      wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
      started = System.nanoTime();
      wakeups.awaitMatch(onTask, Duration.ofSeconds(5));
      wakeups.awaitMatch(onWorkflow, Duration.ofSeconds(5));
      waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs < 1000, "waited " + waitedMs + " ms for a matching admission");
    } finally {
      wakeups.unregister(onTask);
      wakeups.unregister(onWorkflow);
    }
  }

  @Test
  void anAdmissionBetweenRegisteringAndWaitingEndsTheWaitAtOnce() throws Exception {
    TaskWaiter waiter = wakeups.register(templates());
    try {
      wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
      long started = System.nanoTime();
      wakeups.awaitMatch(waiter, Duration.ofSeconds(5));
      long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs < 1000, "waited " + waitedMs + " ms for an admission already landed");
      // The match is consumed: the next wait runs to its interval.
      started = System.nanoTime();
      wakeups.awaitMatch(waiter, Duration.ofMillis(200));
      waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs >= 190, "a consumed match ended the next wait after " + waitedMs + " ms");
    } finally {
      wakeups.unregister(waiter);
    }
  }

  @Test
  void anUnregisteredPollIsNotWoken() throws Exception {
    TaskWaiter waiter = wakeups.register(templates());
    wakeups.unregister(waiter);
    wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
    long started = System.nanoTime();
    wakeups.awaitMatch(waiter, Duration.ofMillis(200));
    long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertTrue(waitedMs >= 190, "waited only " + waitedMs + " ms after unregistering");
  }

  @Test
  void aRunSignalReachesTheWorkflowWaitAndNotTheTaskWait() throws Exception {
    TaskWaiter waiter = wakeups.register(templates());
    try {
      long seen = wakeups.runGeneration();
      wakeups.runProvisionable();
      long started = System.nanoTime();
      wakeups.awaitRun(seen, Duration.ofSeconds(5));
      long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs < 1000, "the workflow wait ran " + waitedMs + " ms past a run signal");

      started = System.nanoTime();
      wakeups.awaitMatch(waiter, Duration.ofMillis(200));
      waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs >= 190, "a run signal woke a task poll after " + waitedMs + " ms");
    } finally {
      wakeups.unregister(waiter);
    }
  }

  @Test
  void anInterruptEndsTheWait() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    TaskWaiter waiter = wakeups.register(templates());
    try {
      Future<Long> waiting = waitInBackground(executor, waiter);
      Thread.sleep(100);
      executor.shutdownNow();
      Exception ended = assertThrows(Exception.class, () -> waiting.get(1, TimeUnit.SECONDS));
      assertTrue(
          ended.getCause() instanceof InterruptedException, String.valueOf(ended.getCause()));
    } finally {
      wakeups.unregister(waiter);
      executor.shutdownNow();
    }
  }
}
