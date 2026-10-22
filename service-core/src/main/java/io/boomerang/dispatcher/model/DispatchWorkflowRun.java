package io.boomerang.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowWorkspace;
import java.util.Map;

/**
 * A workflow run as the dispatcher wire hands it out - on a workflow poll and as the start answer.
 * It carries what a dispatcher needs to provision the run's storage, apart from the public {@link
 * WorkflowRun}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatchWorkflowRun(
    String id,
    RunStatus status,
    RunPhase phase,
    String workflowRef,
    Map<String, String> labels,
    WorkflowWorkspace workspace) {

  /** The wire form of a workflow run. */
  public static DispatchWorkflowRun of(WorkflowRun workflowRun) {
    return new DispatchWorkflowRun(
        workflowRun.getId(),
        workflowRun.getStatus(),
        workflowRun.getPhase(),
        workflowRun.getWorkflowRef(),
        workflowRun.getLabels(),
        workflowRun.getWorkspace());
  }
}
