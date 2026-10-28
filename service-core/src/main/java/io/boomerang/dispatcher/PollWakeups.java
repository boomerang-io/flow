package io.boomerang.dispatcher;

import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.model.ClaimFilter;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The wake-ups the polls held on this engine instance wait on between re-checks, keyed so a signal
 * reaches only the polls it concerns. A held task poll registers its dispatcher and claim filter
 * before its first look and waits on a condition of its own: admitting a task signals the waiters
 * registered under the task's type whose filter the task matches, so an admission costs one claim
 * page per poll that can claim it; a task a dispatcher holds that becomes termination residue
 * signals that dispatcher's waiters whose filter matches, so the termination order goes out on
 * the next look rather than at the next timed re-check. A run submitted with a workspace signals
 * the workflow polls alone. The timed wait stays, as a signal on another instance reaches only
 * that instance's polls. A signal that lands while the poll is still looking is kept on the
 * waiter, so the wait that follows ends at once rather than losing it. The lock never pins a
 * virtual thread, which the polls run on.
 */
@Component
public class PollWakeups {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition runWoken = lock.newCondition();
  private long runGeneration;
  private final Map<TaskType, Set<TaskWaiter>> byType = new EnumMap<>(TaskType.class);
  private final Map<String, Set<TaskWaiter>> byDispatcher = new HashMap<>();

  /**
   * A held task poll: its dispatcher, its filter, its own condition, and whether a matching
   * admission or a termination for its dispatcher has landed.
   */
  public final class TaskWaiter {
    private final String dispatcherId;
    private final ClaimFilter filter;
    private final Condition woken = lock.newCondition();
    private boolean matched;
    private boolean terminationDue;

    private TaskWaiter(String dispatcherId, ClaimFilter filter) {
      this.dispatcherId = dispatcherId;
      this.filter = filter;
    }

    // The in-memory twin of TaskRunService.narrowed: the type within the poll's types, and the
    // task and workflow within its lists when the poll filtered on them.
    private boolean matches(TaskType type, String taskRef, String workflowRef) {
      return filter.types().contains(type)
          && (filter.taskRefs() == null || filter.taskRefs().contains(taskRef))
          && (filter.workflowRefs() == null || filter.workflowRefs().contains(workflowRef));
    }
  }

  /** Register a dispatcher's task poll under each type of its filter, before its first look. */
  public TaskWaiter register(String dispatcherId, ClaimFilter filter) {
    TaskWaiter waiter = new TaskWaiter(dispatcherId, filter);
    lock.lock();
    try {
      for (TaskType type : filter.types()) {
        byType.computeIfAbsent(type, t -> new HashSet<>()).add(waiter);
      }
      byDispatcher.computeIfAbsent(dispatcherId, d -> new HashSet<>()).add(waiter);
    } finally {
      lock.unlock();
    }
    return waiter;
  }

  /** Forget a task poll that has returned. */
  public void unregister(TaskWaiter waiter) {
    lock.lock();
    try {
      for (TaskType type : waiter.filter.types()) {
        remove(byType, type, waiter);
      }
      remove(byDispatcher, waiter.dispatcherId, waiter);
    } finally {
      lock.unlock();
    }
  }

  private static <K> void remove(Map<K, Set<TaskWaiter>> index, K key, TaskWaiter waiter) {
    Set<TaskWaiter> waiters = index.get(key);
    if (waiters != null) {
      waiters.remove(waiter);
      if (waiters.isEmpty()) {
        index.remove(key);
      }
    }
  }

  /**
   * Wait until an admission the waiter's filter matches, or a termination for its dispatcher, has
   * landed since its last wait, or the interval passes; one that landed during the look ends it
   * at once. The match is consumed here; the termination by {@link #takeTerminationDue} at the
   * look that follows.
   */
  public void awaitMatch(TaskWaiter waiter, Duration interval) throws InterruptedException {
    long remaining = interval.toNanos();
    lock.lockInterruptibly();
    try {
      while (!waiter.matched && !waiter.terminationDue && remaining > 0) {
        remaining = waiter.woken.awaitNanos(remaining);
      }
      waiter.matched = false;
    } finally {
      lock.unlock();
    }
  }

  /** Whether a termination for the waiter's dispatcher landed since the last look; consumed. */
  public boolean takeTerminationDue(TaskWaiter waiter) {
    lock.lock();
    try {
      boolean due = waiter.terminationDue;
      waiter.terminationDue = false;
      return due;
    } finally {
      lock.unlock();
    }
  }

  /** A task is claimable: wake the held task polls whose filter can claim it. */
  public void taskAdmitted(TaskType type, String taskRef, String workflowRef) {
    lock.lock();
    try {
      for (TaskWaiter waiter : byType.getOrDefault(type, Set.of())) {
        if (waiter.matches(type, taskRef, workflowRef)) {
          waiter.matched = true;
          waiter.woken.signal();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * A task the dispatcher holds has ended cancelled or timed out, or is parked for retry: wake
   * that dispatcher's held polls whose filter covers it, so its termination order goes out now.
   */
  public void terminationDue(String dispatcherId, TaskType type, String taskRef, String workflowRef) {
    lock.lock();
    try {
      for (TaskWaiter waiter : byDispatcher.getOrDefault(dispatcherId, Set.of())) {
        if (waiter.matches(type, taskRef, workflowRef)) {
          waiter.terminationDue = true;
          waiter.woken.signal();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /** The count of run signals so far, read before a workflow poll's look. */
  public long runGeneration() {
    lock.lock();
    try {
      return runGeneration;
    } finally {
      lock.unlock();
    }
  }

  /** Wait until a run signal lands after {@code seen} or the interval passes. */
  public void awaitRun(long seen, Duration interval) throws InterruptedException {
    long remaining = interval.toNanos();
    lock.lockInterruptibly();
    try {
      while (runGeneration == seen && remaining > 0) {
        remaining = runWoken.awaitNanos(remaining);
      }
    } finally {
      lock.unlock();
    }
  }

  /** A run waits to be provisioned: wake every held workflow poll. */
  public void runProvisionable() {
    lock.lock();
    try {
      runGeneration++;
      runWoken.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
