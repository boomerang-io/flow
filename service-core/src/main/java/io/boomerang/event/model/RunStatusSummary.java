package io.boomerang.event.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.event.enums.EventPayload;
import java.util.Date;
import java.util.Map;

/**
 * The identity and lifecycle of a run: enough to route a status notification and read the run
 * back, and nothing else. Params, results and annotations are deliberately absent - a status
 * event is a pointer to the run, not a copy of it.
 *
 * <p>The single projection step for outbound status CloudEvents: {@link #project} returns this
 * summary or the full public run model according to the configured payload mode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
  "id",
  "workflowRef",
  "workflowRunRef",
  "status",
  "statusReason",
  "phase",
  "labels",
  "creationDate",
  "startTime",
  "duration"
})
public record RunStatusSummary(
    String id,
    String workflowRef,
    String workflowRunRef,
    RunStatus status,
    String statusReason,
    RunPhase phase,
    Map<String, String> labels,
    Date creationDate,
    Date startTime,
    long duration) {

  // The full model leaves the platform, so its secret-typed params go out redacted, and their
  // values are scrubbed from the free text it carries - results, status message, annotations and
  // task spec - where a script that echoes a secret puts it under no param at all. The values are
  // collected before the params are redacted. Every filter copies what it changes, leaving the
  // entity the model was copied from untouched.
  public static Object project(WorkflowRun run, EventPayload payload) {
    if (EventPayload.full != payload) {
      return of(run);
    }
    DataAdapterUtil.scrubWorkflowRunValues(
        run, DataAdapterUtil.sensitiveValues(run, ParamType.secret));
    DataAdapterUtil.filterWorkflowRunValueByFieldType(run, ParamType.secret);
    return run;
  }

  public static Object project(TaskRun run, EventPayload payload) {
    if (EventPayload.full != payload) {
      return of(run);
    }
    DataAdapterUtil.filterTaskRunValues(
        run, DataAdapterUtil.sensitiveValues(run.getParams(), ParamType.secret));
    DataAdapterUtil.filterTaskRunValueByFieldType(run, ParamType.secret);
    return run;
  }

  // A workflow run is its own run: it carries no workflowRunRef. Its statusReason says why it
  // ended as it did, so a consumer acting on a terminal event needs no read back.
  static RunStatusSummary of(WorkflowRun run) {
    return new RunStatusSummary(
        run.getId(),
        run.getWorkflowRef(),
        null,
        run.getStatus(),
        run.getStatusReason(),
        run.getPhase(),
        run.getLabels(),
        run.getCreationDate(),
        run.getStartTime(),
        run.getDuration());
  }

  static RunStatusSummary of(TaskRun run) {
    return new RunStatusSummary(
        run.getId(),
        run.getWorkflowRef(),
        run.getWorkflowRunRef(),
        run.getStatus(),
        run.getStatusReason(),
        run.getPhase(),
        run.getLabels(),
        run.getCreationDate(),
        run.getStartTime(),
        run.getDuration());
  }
}
