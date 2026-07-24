package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunParam;
import java.util.Date;
import java.util.List;

/**
 * Compare-And-Set transitions for WorkflowRuns. Every method is a single-document atomic operation
 * whose query encodes the expected prior state; a {@code null} return means another caller won the
 * transition and this caller must perform no side effects. Every winning transition publishes a
 * {@code WorkflowRunTransition} ApplicationEvent (ids and from/to state only).
 */
public interface WorkflowRunRepositoryCustom {

  /** Return the ready, pending, unclaimed WorkflowRuns awaiting provisioning, oldest first. */
  List<WorkflowRunEntity> findClaimableForProvision(int limit);

  /**
   * Return the completed WorkflowRuns with workspaces still to tear down, oldest first. Claimed
   * and finalized runs are not eligible - completed runs are no longer redelivered on every poll.
   */
  List<WorkflowRunEntity> findClaimableForTeardown(int limit);

  /**
   * Claim one ready, pending, unclaimed WorkflowRun for provisioning: sets phase to queued, writes
   * the claim ownership block (plus {@code agentRef} as the protocol-v1 alias) and increments the
   * claim epoch. Returns the pre-claim document, or {@code null} when another claimant won.
   */
  WorkflowRunEntity tryClaimForProvision(String id, String claimedBy);

  /**
   * Claim one completed, unclaimed WorkflowRun for workspace teardown: writes the claim ownership
   * block and increments the claim epoch, leaving phase untouched. Returns the pre-claim document,
   * or {@code null} when another claimant won.
   */
  WorkflowRunEntity tryClaimForTeardown(String id, String claimedBy);

  /**
   * Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved params in
   * the same guarded write. Returns the pre-image, or {@code null} when already admitted.
   */
  WorkflowRunEntity tryAdmit(String id, List<RunParam> resolvedParams);

  /**
   * Start Compare-And-Set: pending/queued becomes running with the given start time, baking
   * {@code timeoutAt} from the given budget (minutes, {@code null} or 0 = unguarded). The dispatch
   * claim is cleared so the completed-phase teardown claimable becomes eligible later. Returns the
   * document with the transition applied, or {@code null} when another caller already started the
   * run.
   */
  WorkflowRunEntity tryStart(String id, Date startTime, Long timeoutMinutes);

  /**
   * Completion Compare-And-Set: one of the given phases becomes completed with the given terminal
   * status and duration. Exactly one racing completer (advance winner, cancel, timeout) wins - a
   * terminal status can never be overwritten. Returns the pre-image, or {@code null} when the run
   * already completed.
   */
  WorkflowRunEntity tryComplete(
      String id, List<RunPhase> fromPhases, RunStatus status, String statusMessage, long duration);

  /**
   * Mark a running run as timed out (status only; completion follows through the timeout path).
   * Returns the pre-image, or {@code null} when the run is no longer running - a terminal status
   * is never overwritten.
   */
  WorkflowRunEntity tryMarkTimedOut(String id);

  /**
   * Finalize Compare-And-Set: completed becomes finalized. Returns the pre-image, or {@code null}
   * when the run is not completed (never started, still running, or already finalized).
   */
  WorkflowRunEntity tryFinalize(String id);

  /**
   * Pause Compare-And-Set: sets {@code pauseRequestedAt} on a running, not-yet-paused run. Never
   * a status - claiming, admission and the recovery sweeps exclude the flag instead. Returns the
   * pre-image, or {@code null} when the run is not running or already paused.
   */
  WorkflowRunEntity tryPause(String id);

  /**
   * Resume Compare-And-Set: clears {@code pauseRequestedAt}. The caller reconciles afterwards -
   * resume itself changes no status or phase. Returns the pre-image, or {@code null} when the run
   * was not paused.
   */
  WorkflowRunEntity tryResume(String id);

  /** Return the page of running WorkflowRuns whose baked deadline has passed. */
  List<WorkflowRunEntity> findTimedOut(Date now, int limit);

  /**
   * Return the page of running WorkflowRuns started before the given cutoff - the candidates the
   * stalled-run sweep checks for lost graph advances.
   */
  List<WorkflowRunEntity> findRunningStartedBefore(Date startedBefore, int limit);

  /**
   * Return the page of completed, workspace-less WorkflowRuns. With nothing to tear down no agent
   * ever claims them, so the engine finalizes them itself.
   */
  List<WorkflowRunEntity> findFinalizableWithoutWorkspaces(int limit);

  /** Set the awaiting-approval flag without rewriting the rest of the document. */
  void setAwaitingApproval(String id, boolean awaitingApproval);
}
