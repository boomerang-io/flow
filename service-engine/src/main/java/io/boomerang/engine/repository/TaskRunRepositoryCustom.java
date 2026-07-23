package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Compare-And-Set transitions for TaskRuns. Every method is a single-document atomic operation
 * whose query encodes the expected prior state; a {@code null} return means another caller won the
 * transition and this caller must perform no side effects.
 */
public interface TaskRunRepositoryCustom {

  /**
   * Return the page of TaskRuns eligible for claiming by an executor of the given types: ready,
   * pending and unclaimed, oldest first.
   */
  List<TaskRunEntity> findClaimable(List<TaskType> types, int limit);

  /**
   * Claim one eligible TaskRun for the given claimant: sets phase to queued, writes the claim
   * ownership block (plus {@code agentRef} as the protocol-v1 alias) and increments the claim
   * epoch. Returns the pre-claim document (the wire shape the claimant executes), or {@code null}
   * when another claimant won.
   */
  TaskRunEntity tryClaim(String id, String claimedBy);

  /**
   * Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved params in
   * the same guarded write. Returns the pre-image, or {@code null} when already admitted.
   */
  TaskRunEntity tryAdmit(String id, List<RunParam> resolvedParams);

  /**
   * Execution-entry Compare-And-Set: ready + pending/queued becomes running with the given start
   * time. Returns the updated document, or {@code null} on a duplicate dispatch.
   */
  TaskRunEntity tryStartExecution(String id, Date startTime);

  /**
   * Completion Compare-And-Set: any non-completed phase becomes completed with the given duration;
   * status and statusMessage are set only when provided (otherwise the caller-persisted terminal
   * status stands). When claimant identity is provided it is enforced as fencing criteria. Returns
   * the pre-image, or {@code null} when the TaskRun already completed - a terminal status can never
   * be overwritten.
   */
  TaskRunEntity tryComplete(
      String id,
      Optional<RunStatus> status,
      Optional<String> statusMessage,
      long duration,
      Optional<String> claimedBy,
      Optional<Long> claimEpoch);
}
