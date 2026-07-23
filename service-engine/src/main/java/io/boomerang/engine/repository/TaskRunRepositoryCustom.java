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
 * transition and this caller must perform no side effects. Every winning transition publishes a
 * {@code TaskRunTransition} ApplicationEvent (ids and from/to state only).
 */
public interface TaskRunRepositoryCustom {

  /**
   * Return the page of TaskRuns eligible for claiming by an executor of the given types: ready,
   * pending, unclaimed, with any retry backoff elapsed, oldest first.
   */
  List<TaskRunEntity> findClaimable(List<TaskType> types, int limit);

  /**
   * Claim one eligible TaskRun for the given claimant: sets phase to queued, writes the claim
   * ownership block (plus {@code agentRef} as the protocol-v1 alias), increments the claim epoch
   * and clears the consumed retry backoff. The deadline is not baked here - {@code timeoutAt} is
   * set when execution starts, so queue time never consumes the budget. Returns the pre-claim
   * document (the wire shape the claimant executes), or {@code null} when another claimant won.
   */
  TaskRunEntity tryClaim(String id, String claimedBy);

  /**
   * Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved params in
   * the same guarded write. Returns the pre-image, or {@code null} when already admitted.
   */
  TaskRunEntity tryAdmit(String id, List<RunParam> resolvedParams);

  /**
   * Execution-entry Compare-And-Set: ready + pending/queued becomes running with the given start
   * time, baking {@code timeoutAt} from the given budget (minutes, {@code null} or 0 = unguarded)
   * so the runtime holds its full budget from actual start. Returns the document with the
   * transition applied, or {@code null} on a duplicate dispatch.
   */
  TaskRunEntity tryStartExecution(String id, Date startTime, Long timeoutMinutes);

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
      Optional<Long> claimSeq);

  /** Return the page of TaskRuns whose deadline has passed: timeoutAt due, phase queued/running. */
  List<TaskRunEntity> findReapable(Date now, int limit);

  /**
   * Timeout Compare-And-Set: a queued/running TaskRun past its deadline gets status timedout and
   * the given statusMessage in the same atomic write, fenced on the observed claim seq
   * ({@code null} = the run must be unclaimed). The phase is left for the normal end path to
   * complete. Returns the pre-image, or {@code null} when fenced out or already transitioned.
   */
  TaskRunEntity tryTimeout(String id, Long observedClaimSeq, String statusMessage);

  /**
   * Requeue Compare-And-Set: clears {@code claim.by}/{@code claim.at}/{@code claim.leaseExpiresAt}
   * (never {@code claim.seq}) and the baked deadline, writes the retry block and parks the TaskRun
   * back at ready/pending. Fenced on the observed claim seq so a stale claimant cannot requeue the
   * next attempt. Returns the pre-image, or {@code null} when fenced out or already transitioned.
   */
  TaskRunEntity tryRequeue(String id, Long observedClaimSeq, Date retryAfter, int retryCount);

  /**
   * Return whether the run has any in-flight TaskRun: claimed or executing (phase queued/running)
   * or awaiting an external actor (status ready/waiting). Zero in-flight on an active run means
   * the graph advance was lost and must be re-driven.
   */
  boolean existsInFlightByWorkflowRunRef(String workflowRunRef);
}
