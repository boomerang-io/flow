package io.boomerang.engine;

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
}
