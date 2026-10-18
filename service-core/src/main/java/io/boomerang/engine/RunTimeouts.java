package io.boomerang.engine;

import io.boomerang.common.entity.WorkflowRunEntity;
import java.util.Date;

/** The durable timeout deadline: the effective budget baked once at execution start. */
public final class RunTimeouts {

  private RunTimeouts() {}

  /**
   * The absolute deadline for a run/task started at {@code from} with the given budget in minutes,
   * plus the shared grace. Returns {@code null} when no budget is set (unguarded).
   */
  public static Date deadline(Date from, Long timeoutMinutes) {
    return (timeoutMinutes != null && timeoutMinutes > 0)
        ? new Date(from.getTime() + timeoutMinutes * 60000 + EngineConstants.TIMEOUT_GRACE_MILLIS)
        : null;
  }

  /**
   * The time a run has spent executing, in milliseconds: the wall clock since it started, less
   * every stop. A closed stop (a wait or a pause already resumed) moved the deadline forward by its
   * length, so it is read back as the distance between the deadline as stamped at start and the
   * deadline now; a stop still open is measured from the earlier of the two flags. An unguarded
   * run carries no deadline, so only its open stop is excluded.
   */
  public static long executionMillis(WorkflowRunEntity run, Date now) {
    if (run.getStartTime() == null) {
      return 0;
    }
    long elapsed = now.getTime() - run.getStartTime().getTime();
    Date stamped = deadline(run.getStartTime(), run.getTimeout());
    if (stamped != null && run.getTimeoutAt() != null) {
      elapsed -= Math.max(0, run.getTimeoutAt().getTime() - stamped.getTime());
    }
    Date stoppedSince = earlier(run.getWaitingSince(), run.getPauseRequestedAt());
    if (stoppedSince != null) {
      elapsed -= Math.max(0, now.getTime() - stoppedSince.getTime());
    }
    return Math.max(0, elapsed);
  }

  private static Date earlier(Date first, Date second) {
    if (first == null || second == null) {
      return (first != null) ? first : second;
    }
    return first.before(second) ? first : second;
  }
}
