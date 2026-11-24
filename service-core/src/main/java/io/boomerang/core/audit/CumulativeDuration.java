package io.boomerang.core.audit;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunAttempt;
import io.boomerang.common.model.StatusReason;
import io.boomerang.engine.EngineConstants;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * A run's cumulative duration: each of its task runs' time from the dispatcher's start call to its
 * end, added up, so tasks running side by side each count. Only work a dispatcher ran counts: a
 * wait, a task the engine runs itself, a for-each parent (its items count), a task run a scoped
 * retry carried over, and an attempt that never started give nothing.
 */
public abstract class CumulativeDuration {

  private CumulativeDuration() {}

  /** The fields of a task run the sum reads. */
  public record TaskRunTime(
      TaskType type,
      boolean foreach,
      String parentRef,
      String reusedFromRef,
      RunPhase phase,
      Date startTime,
      long duration,
      String statusReason,
      List<RunAttempt> attempts) {}

  /** Return the run's cumulative duration in milliseconds, a task still running counting to now. */
  public static long millis(List<TaskRunTime> taskRuns, Instant now) {
    return taskRuns.stream().mapToLong(taskRun -> millis(taskRun, now)).sum();
  }

  static long millis(TaskRunTime taskRun, Instant now) {
    if (taskRun.type() == null
        || !EngineConstants.REQUEUEABLE_TYPES.contains(taskRun.type())
        || (taskRun.foreach() && taskRun.parentRef() == null)
        || taskRun.reusedFromRef() != null) {
      return 0;
    }
    long total = 0;
    // A requeue keeps startTime, so an attempt's start is trusted only when it is after both its
    // own claim and the end of the attempt before it.
    Date previousEnd = null;
    if (taskRun.attempts() != null) {
      for (RunAttempt attempt : taskRun.attempts()) {
        if (!requeueable(attempt.getStatusReason()) && started(attempt, previousEnd)) {
          total += Math.max(0, attempt.getDuration());
        }
        if (attempt.getEndedAt() != null) {
          previousEnd = attempt.getEndedAt();
        }
      }
    }
    return total + Math.max(0, current(taskRun, previousEnd, now));
  }

  // The attempt the task run is on: its own status, reason and duration.
  private static long current(TaskRunTime taskRun, Date previousEnd, Instant now) {
    Date start = taskRun.startTime();
    if (requeueable(taskRun.statusReason())
        || (previousEnd != null && (start == null || !start.after(previousEnd)))) {
      return 0;
    }
    if (RunPhase.completed.equals(taskRun.phase())) {
      return taskRun.duration();
    }
    return (RunPhase.running.equals(taskRun.phase()) && start != null)
        ? now.toEpochMilli() - start.getTime()
        : 0;
  }

  // An attempt's start is its end less its duration: the start call's time, or its claim's when
  // it never started. A start no later than its claim or the previous attempt's end never happened.
  private static boolean started(RunAttempt attempt, Date previousEnd) {
    if (attempt.getEndedAt() == null) {
      return true;
    }
    long start = attempt.getEndedAt().getTime() - attempt.getDuration();
    return (attempt.getClaimedAt() == null || start > attempt.getClaimedAt().getTime())
        && (previousEnd == null || start > previousEnd.getTime());
  }

  private static boolean requeueable(String statusReason) {
    return statusReason != null && StatusReason.REQUEUEABLE.contains(statusReason);
  }
}
