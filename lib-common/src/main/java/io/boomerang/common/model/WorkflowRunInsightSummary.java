package io.boomerang.common.model;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A workspace's run statistics over a period, computed from the runs it still holds. The
 * previous period is the same length ending where this one starts, so every headline number can
 * carry a comparison. Durations and waits are milliseconds; a percentile over no runs is 0.
 */
@Data
public class WorkflowRunInsightSummary {

  private Date from;
  private Date to;
  private Date previousFrom;
  private Date previousTo;
  private Totals totals = new Totals();
  private Totals previous = new Totals();
  /** One entry per calendar day (UTC) of the period, days without runs included. */
  private List<Day> daily = new LinkedList<>();
  /** One row per workflow that ran in the period, worst success rate first. */
  private List<WorkflowRow> workflows = new LinkedList<>();

  @Data
  public static class Totals {
    private long runs;
    private long succeeded;
    private long failed;
    private long timedOut;
    private long cancelled;
    /** Runs in a terminal status other than the four above (invalid, skipped). */
    private long other;
    /** Runs not yet completed. */
    private long inFlight;
    /** succeeded / (succeeded + failed + timedOut + invalid); null when nothing has finished. */
    private Double successRate;
    /** Over succeeded runs. */
    private long p50Duration;
    private long p95Duration;
    /** Over every completed run. */
    private long maxDuration;
    /** Start time minus creation time, over runs that started. */
    private long p50QueueWait;
    private long p95QueueWait;
    private Map<String, Long> byTrigger = new LinkedHashMap<>();
  }

  @Data
  public static class Day {
    /** ISO date, UTC. */
    private String date;
    private long succeeded;
    private long failed;
    private long timedOut;
    private long cancelled;
    private long other;
  }

  @Data
  public static class WorkflowRow {
    private String workflowRef;
    private String workflowName;
    private long runs;
    private long succeeded;
    private long failed;
    private long timedOut;
    private long cancelled;
    private long other;
    private Double successRate;
    private long p5Duration;
    private long p50Duration;
    private long p95Duration;
    private long maxDuration;
    /** The run timeout in effect on the newest run, in minutes; null when unset. */
    private Long timeoutMinutes;
    private Date lastFailureDate;
    private String lastFailureRunRef;
    /** The last seven calendar days ending at the period's end, oldest first. */
    private List<Day> recent = new LinkedList<>();
  }
}
