package io.boomerang.engine.model;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunResult;
import java.util.List;
import java.util.Set;

/**
 * How the engine ends a TaskRun it decided the end of itself, written in the completion
 * Compare-And-Set: from one of the {@code from} statuses, the TaskRun completes with this status
 * and, where set, its reason, message, results and decision value. A TaskRun that has moved on -
 * completed, timed out, requeued - matches no {@code from} status, so the end changes nothing.
 */
public record TaskOutcome(
    Set<RunStatus> from,
    RunStatus status,
    String statusReason,
    String statusMessage,
    List<RunResult> results,
    String decisionValue) {

  /**
   * A task the graph does not reach. {@code skipped} is accepted alongside {@code notstarted} so a
   * TaskRun an earlier engine left skipped but never completed still completes.
   */
  public static TaskOutcome skipped() {
    return new TaskOutcome(
        Set.of(RunStatus.notstarted, RunStatus.skipped), RunStatus.skipped, null, null, null, null);
  }

  /** A task admission refuses before it is claimable: failed with a typed reason, or invalid. */
  public static TaskOutcome refused(RunStatus status, String statusReason, String statusMessage) {
    return new TaskOutcome(
        Set.of(RunStatus.notstarted), status, statusReason, statusMessage, null, null);
  }

  /** What an engine-run task decided while executing or waiting, read off the TaskRun it ran on. */
  public static TaskOutcome executed(TaskRunEntity taskRun) {
    return new TaskOutcome(
        Set.of(RunStatus.running, RunStatus.waiting),
        taskRun.getStatus(),
        taskRun.getStatusReason(),
        taskRun.getStatusMessage(),
        taskRun.getResults(),
        taskRun.getDecisionValue());
  }

  /** A for-each parent's outcome, built from its items. */
  public static TaskOutcome ofItems(
      RunStatus status, String statusReason, String statusMessage, List<RunResult> results) {
    return new TaskOutcome(
        Set.of(RunStatus.running), status, statusReason, statusMessage, results, null);
  }
}
