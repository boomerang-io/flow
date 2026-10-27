package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * workflow when it filtered on them - at a termination of a task its dispatcher holds, at the
 * interval, or at an interrupt; an admission another filter would claim, or a termination for
 * another dispatcher, leaves it waiting, and a signal that landed after the poll registered and
 * before it waited ends the wait at once. A run signal reaches the workflow waits alone.
 */
class PollWakeupsTest {

  private static final String DISPATCHER = "dispatcher-1";
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

  private long waitedMillis(TaskWaiter waiter, Duration interval) throws InterruptedException {
    long started = System.nanoTime();
    wakeups.awaitMatch(waiter, interval);
    return Duration.ofNanos(System.nanoTime() - started).toMillis();
  }

  @Test
  void aMatchingAdmissionEndsTheWaitBeforeTheInterval() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    TaskWaiter waiter = wakeups.register(DISPATCHER, templates());
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
    TaskWaiter waiter = wakeups.register(DISPATCHER, templates());
    try {
      wakeups.taskAdmitted(TaskType.custom, TASK, WORKFLOW);
      long waitedMs = waitedMillis(waiter, Duration.ofMillis(200));
      assertTrue(waitedMs >= 190, "waited only " + waitedMs + " ms after a custom admission");
    } finally {
      wakeups.unregister(waiter);
    }
  }

  @Test
  void aTaskOrWorkflowFilterIsHonoured() throws Exception {
    TaskWaiter onTask =
        wakeups.register(
            DISPATCHER, new ClaimFilter(List.of(TaskType.template), List.of(TASK), null));
    TaskWaiter onWorkflow =
        wakeups.register(
            DISPATCHER, new ClaimFilter(List.of(TaskType.template), null, List.of(WORKFLOW)));
    try {
      wakeups.taskAdmitted(TaskType.template, "other-task", "other-wf");
      long waitedMs =
          waitedMillis(onTask, Duration.ofMillis(150))
              + waitedMillis(onWorkflow, Duration.ofMillis(150));
      assertTrue(waitedMs >= 290, "waited only " + waitedMs + " ms: a filtered poll was woken");

      wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
      waitedMs =
          waitedMillis(onTask, Duration.ofSeconds(5))
              + waitedMillis(onWorkflow, Duration.ofSeconds(5));
      assertTrue(waitedMs < 1000, "waited " + waitedMs + " ms for a matching admission");
    } finally {
      wakeups.unregister(onTask);
      wakeups.unregister(onWorkflow);
    }
  }

  @Test
  void anAdmissionBetweenRegisteringAndWaitingEndsTheWaitAtOnce() throws Exception {
    TaskWaiter waiter = wakeups.register(DISPATCHER, templates());
    try {
      wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
      long waitedMs = waitedMillis(waiter, Duration.ofSeconds(5));
      assertTrue(waitedMs < 1000, "waited " + waitedMs + " ms for an admission already landed");
      // The match is consumed: the next wait runs to its interval.
      waitedMs = waitedMillis(waiter, Duration.ofMillis(200));
      assertTrue(waitedMs >= 190, "a consumed match ended the next wait after " + waitedMs + " ms");
    } finally {
      wakeups.unregister(waiter);
    }
  }

  @Test
  void aTerminationForTheHolderEndsTheWaitAndIsTakenAtTheNextLook() throws Exception {
    TaskWaiter holder = wakeups.register(DISPATCHER, templates());
    TaskWaiter other = wakeups.register("dispatcher-2", templates());
    try {
      wakeups.terminationDue(DISPATCHER, TaskType.template, TASK, WORKFLOW);
      long waitedMs = waitedMillis(holder, Duration.ofSeconds(5));
      assertTrue(waitedMs < 1000, "the holder waited " + waitedMs + " ms past a termination");
      assertTrue(wakeups.takeTerminationDue(holder), "the holder's next look runs the pages");
      assertFalse(wakeups.takeTerminationDue(holder), "a termination is taken once");

      waitedMs = waitedMillis(other, Duration.ofMillis(200));
      assertTrue(waitedMs >= 190, "another dispatcher's poll was woken after " + waitedMs + " ms");
      assertFalse(wakeups.takeTerminationDue(other), "another dispatcher's poll has no termination");

      // A termination of a task outside the poll's filter is not its order to carry.
      wakeups.terminationDue(DISPATCHER, TaskType.custom, TASK, WORKFLOW);
      waitedMs = waitedMillis(holder, Duration.ofMillis(200));
      assertTrue(waitedMs >= 190, "a custom termination woke a template poll after " + waitedMs + " ms");
    } finally {
      wakeups.unregister(holder);
      wakeups.unregister(other);
    }
  }

  @Test
  void anUnregisteredPollIsNotWoken() throws Exception {
    TaskWaiter waiter = wakeups.register(DISPATCHER, templates());
    wakeups.unregister(waiter);
    wakeups.taskAdmitted(TaskType.template, TASK, WORKFLOW);
    wakeups.terminationDue(DISPATCHER, TaskType.template, TASK, WORKFLOW);
    long waitedMs = waitedMillis(waiter, Duration.ofMillis(200));
    assertTrue(waitedMs >= 190, "waited only " + waitedMs + " ms after unregistering");
  }

  @Test
  void aRunSignalReachesTheWorkflowWaitAndNotTheTaskWait() throws Exception {
    TaskWaiter waiter = wakeups.register(DISPATCHER, templates());
    try {
      long seen = wakeups.runGeneration();
      wakeups.runProvisionable();
      long started = System.nanoTime();
      wakeups.awaitRun(seen, Duration.ofSeconds(5));
      long waitedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
      assertTrue(waitedMs < 1000, "the workflow wait ran " + waitedMs + " ms past a run signal");

      waitedMs = waitedMillis(waiter, Duration.ofMillis(200));
      assertTrue(waitedMs >= 190, "a run signal woke a task poll after " + waitedMs + " ms");
    } finally {
      wakeups.unregister(waiter);
    }
  }

  @Test
  void anInterruptEndsTheWait() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    TaskWaiter waiter = wakeups.register(DISPATCHER, templates());
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
