package io.boomerang.engine;

import io.boomerang.common.enums.TaskType;
import java.util.EnumSet;
import java.util.Set;

/** Shared operational constants for the engine's execution and sweep machinery. */
public final class EngineConstants {

  private EngineConstants() {}

  /** Grace added on top of a timeout budget so a run at exactly its budget is not reaped. */
  public static final long TIMEOUT_GRACE_MILLIS = 5000L;

  /** Page size for the level-triggered watcher/dispatcher sweeps. */
  public static final int SWEEP_PAGE_SIZE = 50;

  /**
   * The dispatched types the engine requeues for another attempt: on timeout (the crash recovery
   * for a killed claimant), and when a dispatcher reports it could not start the task. Gates,
   * waits and inline system tasks time out terminally, as they always have.
   */
  public static final Set<TaskType> REQUEUEABLE_TYPES =
      EnumSet.of(
          TaskType.template,
          TaskType.custom,
          TaskType.script,
          TaskType.generic,
          TaskType.ai,
          TaskType.uploadartifact,
          TaskType.downloadartifact);

  /** Attempts a requeueable task gets beyond its first before it fails. */
  public static final int MAX_RETRIES = 3;
}
