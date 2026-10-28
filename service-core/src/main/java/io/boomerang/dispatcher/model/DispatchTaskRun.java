package io.boomerang.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunAttempt;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.WorkflowWorkspace;
import java.util.List;
import java.util.Map;

/**
 * A task run as the dispatcher wire hands it out - on a task poll and as the start and end
 * answers. It carries what a dispatcher needs to run or terminate the task, apart from the public
 * {@link TaskRun}, which also carries the run's history for people and hosts. {@code phase} and
 * {@code status} make it an order to run or to terminate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatchTaskRun(
    String id,
    TaskType type,
    String name,
    Map<String, String> labels,
    Long timeout,
    List<RunParam> params,
    List<RunResult> results,
    WorkflowWorkspace workspace,
    TaskRunSpec spec,
    RunStatus status,
    RunPhase phase,
    String taskRef,
    String workflowRef,
    String workflowRunRef,
    // Set only on an item of a for-each task: its position from 0.
    Integer index,
    // On a terminate order, why the task ended; absent on a run order.
    String statusReason,
    // The attempt this order is for: 1, plus one per requeue of any cause.
    Integer attempt,
    // The attempts a requeue superseded, oldest first; absent on a first attempt.
    List<RunAttempt> attempts) {

  /** The wire form of a task run the engine has already prepared for hand-out. */
  public static DispatchTaskRun of(TaskRun taskRun) {
    return new DispatchTaskRun(
        taskRun.getId(),
        taskRun.getType(),
        taskRun.getName(),
        taskRun.getLabels(),
        taskRun.getTimeout(),
        taskRun.getParams(),
        taskRun.getResults(),
        taskRun.getWorkspace(),
        taskRun.getSpec(),
        taskRun.getStatus(),
        taskRun.getPhase(),
        taskRun.getTaskRef(),
        taskRun.getWorkflowRef(),
        taskRun.getWorkflowRunRef(),
        taskRun.getIndex(),
        taskRun.getStatusReason(),
        taskRun.getAttempt(),
        taskRun.getAttempts());
  }
}
