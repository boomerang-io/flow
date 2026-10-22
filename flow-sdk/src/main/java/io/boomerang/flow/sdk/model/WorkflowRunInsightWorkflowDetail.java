package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One workflow's runs over a period: where a typical run spends its time, per task, and why runs
 * did not succeed. Durations are milliseconds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunInsightWorkflowDetail(
    String workflowRef,
    String workflowName,
    Instant from,
    Instant to,
    long runs,
    long p50QueueWait,
    long p95QueueWait,
    long retriedRuns,
    List<TaskRow> tasks,
    List<Failure> failures,
    WorkflowRunInsightUsage usage) {

  /** One task name, in the order a typical run reaches it; usage null unless an ai task. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TaskRow(
      String name,
      String taskRef,
      long runs,
      long failed,
      long p50Duration,
      long p95Duration,
      Long totalTokens,
      Double cost) {}

  /** Runs that did not succeed, grouped by outcome, failing task and reason; most common first. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Failure(
      String status,
      String taskName,
      String reason,
      long count,
      String lastRunRef,
      Instant lastDate) {}
}
