package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.model.RunParam;
import java.util.Date;
import java.util.List;

/**
 * Compare-And-Set transitions for WorkflowRuns. Every method is a single-document atomic operation
 * whose query encodes the expected prior state; a {@code null} return means another caller won the
 * transition and this caller must perform no side effects.
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
   * Start Compare-And-Set: pending/queued becomes running with the given start time. The dispatch
   * claim is cleared so the completed-phase teardown claimable becomes eligible later. Returns the
   * updated document, or {@code null} when another caller already started the run.
   */
  WorkflowRunEntity tryStart(String id, Date startTime);

  /** Set the awaiting-approval flag without rewriting the rest of the document. */
  void setAwaitingApproval(String id, boolean awaitingApproval);
}
