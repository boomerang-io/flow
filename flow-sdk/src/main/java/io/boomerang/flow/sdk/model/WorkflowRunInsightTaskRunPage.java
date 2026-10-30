package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One page of task runs read across workspaces, newest first, for a platform-wide grant. A row is
 * a task run's identity and lifecycle with the workspace it belongs to, never its params, results
 * or spec: the workspace's own task-run routes read those.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunInsightTaskRunPage(
    List<TaskRunRow> content, int number, int size, long totalElements, int totalPages) {

  /** One task run with its workspace. {@code foreach} marks a for-each parent; an item has its {@code index}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TaskRunRow(
      String id,
      String workspace,
      String name,
      TaskType type,
      String taskRef,
      RunStatus status,
      RunPhase phase,
      String statusReason,
      Instant creationDate,
      Instant startTime,
      long duration,
      Integer attempt,
      Boolean foreach,
      Integer index,
      String workflowRef,
      String workflowName,
      String workflowRunRef,
      Map<String, String> labels) {}
}
