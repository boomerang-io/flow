package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A workspace's task runs over a period, grouped by one key and optionally by time, computed from
 * the runs it still holds. A task run counts once it completed, unless it was skipped; a for-each
 * task counts its items, under its own name. Durations are milliseconds over succeeded task runs; a
 * statistic over none is 0.
 */
@Data
@JsonInclude(Include.NON_NULL)
public class WorkflowRunInsightTasks {

  private Date from;
  private Date to;
  /** The grouping asked for, as given: task, type, workflow, param:<name> or label:<key>. */
  private String groupBy;
  /** hour, day or month (UTC), when the rows are also grouped by time. */
  private String bucket;
  /** label:<key>, when each row also measures the wall clock of the runs sharing that label. */
  private String spanBy;
  /** Rows past the cap were left out; narrow the filters or the period. */
  private boolean truncated;
  /** By bucket, then most task runs first. */
  private List<Row> rows = new LinkedList<>();
  /** The slowest task runs over every row, slowest first, when asked for. */
  private List<SlowTaskRun> slowest;

  @Data
  @JsonInclude(Include.NON_NULL)
  public static class Row {
    /** The group's value; null for task runs that have none, and for every one when ungrouped. */
    private String key;
    /** The start of the bucket, when bucketed. */
    private Date bucket;
    private long taskRuns;
    private long succeeded;
    /** Failed, timed out or invalid. */
    private long failed;
    private long cancelled;
    private long meanDuration;
    private long p50Duration;
    private long p95Duration;
    private long maxDuration;
    /** With spanBy: how many label values the row's task runs carry. */
    private Long spans;
    /** With spanBy: the first start to the last end of each label value's task runs. */
    private Long p50Span;
    private Long p95Span;
    private Long maxSpan;
  }

  @Data
  @JsonInclude(Include.NON_NULL)
  public static class SlowTaskRun {
    private String taskRunRef;
    private String workflowRunRef;
    private String workflowRef;
    private String name;
    private String status;
    private Date startTime;
    private long duration;
    /** The values of the params asked for; a secret param is never included. */
    private Map<String, Object> params = new LinkedHashMap<>();
  }
}
