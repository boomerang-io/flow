package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A workflow run as the public API answers it: the execution record of one submission. {@code
 * status} is the outcome a caller acts on; {@code phase} is where the run is in its lifecycle.
 * {@code statusReason} is the typed cause the engine ended it with, when it had one. {@code tasks} is present only when read with them. A secret param's value reads as the server's
 * redaction marker.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRun(
    String id,
    Instant creationDate,
    RunStatus status,
    RunPhase phase,
    Boolean paused,
    Instant startTime,
    Long duration,
    String statusMessage,
    String statusReason,
    Boolean awaitingApproval,
    Long timeout,
    Long retries,
    Boolean debug,
    String workflowRef,
    String workflowName,
    String workflowDisplayName,
    Integer workflowVersion,
    String workflowRevisionRef,
    String trigger,
    String initiatedByRef,
    String initiatedByWorkflowRunRef,
    String initiatedByName,
    RetryFrom retryFrom,
    Map<String, String> labels,
    Map<String, Object> annotations,
    List<RunParam> params,
    List<RunResult> results,
    WorkflowWorkspace workspace,
    List<TaskRun> tasks) {

  // Params are left out: a param may carry a secret.
  @Override
  public String toString() {
    return "WorkflowRun[id="
        + id
        + ", workflowRef="
        + workflowRef
        + ", status="
        + status
        + ", phase="
        + phase
        + "]";
  }
}
