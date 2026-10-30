package io.boomerang.engine;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
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
   * How long a claimed due wait (a sleep, an acquirelock retry) stays claimed: an instance that
   * stops before the task ends leaves it due again after this, for any instance to resume.
   */
  public static final long WAIT_RESUME_LEASE_MILLIS = 60_000L;

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

  /** Claims a workflow run's workspace provisioning gets before the run fails. */
  public static final int MAX_PROVISION_ATTEMPTS = 3;

  /** The most retries of a reported failure a task node may ask for ({@code retry.max}). */
  public static final int MAX_FAILURE_RETRIES = 10;

  /**
   * The types whose execution is a wait on something outside the engine's control: a person, an
   * event, a child run, the clock, a lock. While every in-flight task of a run is one of these the
   * run clock is stopped, and such a task is bounded only by a timeout its author declared.
   */
  public static final Set<TaskType> WAITING_TYPES =
      EnumSet.of(
          TaskType.approval,
          TaskType.manual,
          TaskType.eventwait,
          TaskType.runworkflow,
          TaskType.sleep,
          TaskType.acquirelock);

  /**
   * The statuses a run ends with. A run carrying one while still in the {@code running} phase has
   * ended its graph and is running its finally tasks: the finally stage.
   */
  public static final Set<RunStatus> TERMINAL_RUN_STATUSES =
      EnumSet.of(
          RunStatus.succeeded,
          RunStatus.failed,
          RunStatus.invalid,
          RunStatus.skipped,
          RunStatus.cancelled,
          RunStatus.timedout);

  /** The {@code workflowrun} setting capping the finally stage, in minutes, and its default. */
  public static final String FINALLY_TIMEOUT_SETTING = "finally.timeout";

  public static final int DEFAULT_FINALLY_TIMEOUT_MINUTES = 5;

  /**
   * Whether the run's graph has ended and its finally tasks are what still runs: the graph-end
   * write stamps {@code graphEndedAt} and leaves the phase running. The status alone cannot say,
   * since the timeout sweep marks a run timed out before its completion lands.
   */
  public static boolean inFinallyStage(WorkflowRunEntity run) {
    return run != null
        && RunPhase.running.equals(run.getPhase())
        && run.getGraphEndedAt() != null;
  }
}
