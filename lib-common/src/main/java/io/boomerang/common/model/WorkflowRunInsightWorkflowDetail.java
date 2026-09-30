package io.boomerang.common.model;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;

/**
 * One workflow's run statistics over a period, from the runs and task runs still held: where a
 * typical run spends its time, and why runs did not succeed. Durations are milliseconds.
 */
@Data
public class WorkflowRunInsightWorkflowDetail {

  private String workflowRef;
  private String workflowName;
  private Date from;
  private Date to;
  private long runs;
  /** Start time minus creation time, over runs that started. */
  private long p50QueueWait;
  private long p95QueueWait;
  /** Runs that were retried at least once. */
  private long retriedRuns;
  /** One row per task name, in the order a typical run reaches them (first creation). */
  private List<TaskRow> tasks = new LinkedList<>();
  /** Runs that did not succeed, grouped by outcome, failing task and reason; most common first. */
  private List<Failure> failures = new LinkedList<>();

  @Data
  public static class TaskRow {
    private String name;
    private String taskRef;
    private long runs;
    private long failed;
    /** Over succeeded task runs. */
    private long p50Duration;
    private long p95Duration;
  }

  @Data
  public static class Failure {
    /** The run's terminal status: failed, timedout, invalid or cancelled. */
    private String status;
    /** The first task run in the run that did not succeed; null when none did. */
    private String taskName;
    /** That task run's status message or reason, else the run's; null when neither is set. */
    private String reason;
    private long count;
    private String lastRunRef;
    private Date lastDate;
  }
}
