package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A workspace's run statistics over a period, from the runs it still holds, with the same-length
 * previous period for comparison. Durations and waits are milliseconds; a percentile over no runs
 * is 0.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowRunInsightSummary(
    Instant from,
    Instant to,
    Instant previousFrom,
    Instant previousTo,
    Totals totals,
    Totals previous,
    List<Day> daily,
    List<WorkflowRow> workflows,
    WorkflowRunInsightUsage usage) {

  /** {@code successRate} is null when nothing has finished. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Totals(
      long runs,
      long succeeded,
      long failed,
      long timedOut,
      long cancelled,
      long other,
      long inFlight,
      Double successRate,
      long p50Duration,
      long p95Duration,
      long maxDuration,
      long p50QueueWait,
      long p95QueueWait,
      Map<String, Long> byTrigger) {}

  /** One calendar day (UTC) of the period, as an ISO date. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Day(
      String date, long succeeded, long failed, long timedOut, long cancelled, long other) {}

  /** One workflow that ran in the period; {@code recent} is its last seven days, oldest first. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WorkflowRow(
      String workflowRef,
      String workflowName,
      long runs,
      long succeeded,
      long failed,
      long timedOut,
      long cancelled,
      long other,
      Double successRate,
      long p5Duration,
      long p50Duration,
      long p95Duration,
      long maxDuration,
      Long timeoutMinutes,
      Instant lastFailureDate,
      String lastFailureRunRef,
      List<Day> recent) {}
}
