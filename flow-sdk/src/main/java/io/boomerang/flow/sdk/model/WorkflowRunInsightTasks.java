package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A workspace's task runs over a period, grouped by one key and optionally by time. A task run
 * counts once it completed, unless skipped; a for-each task counts its items under its own name.
 * Durations are milliseconds over succeeded task runs. {@code truncated} means rows past the
 * server's cap were left out.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunInsightTasks(
    Instant from,
    Instant to,
    String groupBy,
    String bucket,
    String spanBy,
    boolean truncated,
    List<Row> rows,
    List<SlowTaskRun> slowest) {

  /**
   * One group, by bucket then most task runs first. {@code key} is null for task runs without
   * the group's value; the span figures are present with {@code spanBy}, the usage ones when the
   * row has ai task runs.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Row(
      String key,
      Instant bucket,
      long taskRuns,
      long succeeded,
      long failed,
      long cancelled,
      long meanDuration,
      long p50Duration,
      long p95Duration,
      long maxDuration,
      Long spans,
      Long p50Span,
      Long p95Span,
      Long maxSpan,
      Long aiTaskRuns,
      Long promptTokens,
      Long completionTokens,
      Long totalTokens,
      Long reasoningTokens,
      Long cachedPromptTokens,
      Double cost) {}

  /** One of the slowest task runs over every row. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SlowTaskRun(
      String taskRunRef,
      String workflowRunRef,
      String workflowRef,
      String name,
      String status,
      Instant startTime,
      long duration) {}
}
