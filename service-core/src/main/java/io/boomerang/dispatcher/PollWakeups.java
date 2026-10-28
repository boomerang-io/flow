package io.boomerang.dispatcher;

import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.model.ClaimFilter;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The wake-ups the polls held on this engine instance wait on between re-checks, keyed so a signal
 * reaches only the polls it concerns. A held task poll registers its claim filter before its first
 * look and waits on a condition of its own; admitting a task signals the waiters registered under
 * the task's type whose filter the task matches, and no other, so an admission costs one claim
 * page per poll that can claim it. A run submitted with a workspace signals the workflow polls
 * alone. The timed wait stays, as an admission on another instance signals only that instance's
 * polls. A signal that lands while the poll is still looking is kept on the waiter, so the wait
 * that follows ends at once rather than losing it. The lock never pins a virtual thread, which the
 * polls run on.
 */
@Component
public class PollWakeups {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition runWoken = lock.newCondition();
  private long runGeneration;
  private final Map<TaskType, Set<TaskWaiter>> taskWaiters = new EnumMap<>(TaskType.class);

  /** A held task poll: its filter, its own condition and whether a matching admission landed. */
  public final class TaskWaiter {
    private final ClaimFilter filter;
    private final Condition woken = lock.newCondition();
    private boolean matched;

    private TaskWaiter(ClaimFilter filter) {
      this.filter = filter;
    }

    // The in-memory twin of TaskRunService.claimableQuery's narrowing: the type within the poll's
    // types, and the task and workflow within its lists when the poll filtered on them.
    private boolean matches(TaskType type, String taskRef, String workflowRef) {
      return filter.types().contains(type)
          && (filter.taskRefs() == null || filter.taskRefs().contains(taskRef))
          && (filter.workflowRefs() == null || filter.workflowRefs().contains(workflowRef));
    }
  }

  /** Register a task poll under each type of its filter, before its first look. */
  public TaskWaiter register(ClaimFilter filter) {
    TaskWaiter waiter = new TaskWaiter(filter);
    lock.lock();
    try {
      for (TaskType type : filter.types()) {
        taskWaiters.computeIfAbsent(type, t -> new HashSet<>()).add(waiter);
      }
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
        Set<TaskWaiter> waiters = taskWaiters.get(type);
        if (waiters != null) {
          waiters.remove(waiter);
          if (waiters.isEmpty()) {
            taskWaiters.remove(type);
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Wait until an admission the waiter's filter matches has landed since its last wait, or the
   * interval passes; a match that landed during the look ends it at once. The match is consumed.
   */
  public void awaitMatch(TaskWaiter waiter, Duration interval) throws InterruptedException {
    long remaining = interval.toNanos();
    lock.lockInterruptibly();
    try {
      while (!waiter.matched && remaining > 0) {
        remaining = waiter.woken.awaitNanos(remaining);
      }
      waiter.matched = false;
    } finally {
      lock.unlock();
    }
  }

  /** A task is claimable: wake the held task polls whose filter can claim it. */
  public void taskAdmitted(TaskType type, String taskRef, String workflowRef) {
    lock.lock();
    try {
      for (TaskWaiter waiter : taskWaiters.getOrDefault(type, Set.of())) {
        if (waiter.matches(type, taskRef, workflowRef)) {
          waiter.matched = true;
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
