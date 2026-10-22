package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One task's run inside a workflow run. {@code parentRef} and {@code index} are set on an item of
 * a for-each task; {@code reusedFromRef} on a task a scoped retry carried over instead of running;
 * {@code attempt} counts the dispatcher's attempts, absent on a task no dispatcher claimed. A row
 * from {@code TaskRuns.query} leaves out params, results, spec, annotations and the status
 * message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRun(
    String id,
    TaskType type,
    String name,
    Map<String, String> labels,
    Map<String, Object> annotations,
    Instant creationDate,
    Instant startTime,
    Long duration,
    Long timeout,
    List<RunParam> params,
    List<RunResult> results,
    WorkflowWorkspace workspace,
    TaskRunSpec spec,
    RunStatus status,
    RunPhase phase,
    String statusMessage,
    String statusReason,
    String taskRef,
    Integer taskVersion,
    String workflowRef,
    String workflowRevisionRef,
    String workflowRunRef,
    String workflowName,
    String parentRef,
    Integer index,
    String reusedFromRef,
    Integer attempt) {

  // Params and the spec are left out: either may carry a secret.
  @Override
  public String toString() {
    return "TaskRun[id="
        + id
        + ", name="
        + name
        + ", type="
        + type
        + ", status="
        + status
        + ", phase="
        + phase
        + "]";
  }
}
