package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.TaskType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the Insights routes narrow and group by, mapped to their parameters. Each route reads its
 * own part: the period, workflows and labels on all three; statuses and triggers on the summary;
 * task names, types, grouping, bucket, span and slowest on the task-run route.
 *
 * <pre>{@code
 * InsightsQuery.period(t0, t1).groupByLabel("boomerang.io/item").bucket(Bucket.day)
 * }</pre>
 */
public final class InsightsQuery {

  private final List<String> workflows = new ArrayList<>();
  private final List<String> labels = new ArrayList<>();
  private final List<RunStatus> statuses = new ArrayList<>();
  private final List<String> triggers = new ArrayList<>();
  private final List<String> tasks = new ArrayList<>();
  private final List<TaskType> types = new ArrayList<>();
  private Instant from;
  private Instant to;
  private String groupBy;
  private Bucket bucket;
  private String spanBy;
  private Integer slowest;

  private InsightsQuery() {}

  /** The server's default period: the last 90 days. */
  public static InsightsQuery all() {
    return new InsightsQuery();
  }

  public static InsightsQuery period(Instant from, Instant to) {
    return all().from(from).to(to);
  }

  public InsightsQuery from(Instant from) {
    this.from = from;
    return this;
  }

  public InsightsQuery to(Instant to) {
    this.to = to;
    return this;
  }

  /** Runs of these workflows, by id. */
  public InsightsQuery workflows(String... ids) {
    this.workflows.addAll(Arrays.asList(ids));
    return this;
  }

  /** Runs carrying every one of these labels, each as {@code key=value}. */
  public InsightsQuery labels(String... labels) {
    this.labels.addAll(Arrays.asList(labels));
    return this;
  }

  public InsightsQuery label(String key, String value) {
    return labels(key + "=" + value);
  }

  /** Summary only. */
  public InsightsQuery statuses(RunStatus... statuses) {
    this.statuses.addAll(Arrays.asList(statuses));
    return this;
  }

  /** Summary only. */
  public InsightsQuery triggers(String... triggers) {
    this.triggers.addAll(Arrays.asList(triggers));
    return this;
  }

  /** Task runs only: the task node names to count. */
  public InsightsQuery tasks(String... names) {
    this.tasks.addAll(Arrays.asList(names));
    return this;
  }

  /** Task runs only: the task types to count. */
  public InsightsQuery types(TaskType... types) {
    this.types.addAll(Arrays.asList(types));
    return this;
  }

  /** Task runs only: {@code task}, {@code type}, {@code workflow} or {@code label:<key>}. */
  public InsightsQuery groupBy(String groupBy) {
    this.groupBy = groupBy;
    return this;
  }

  /** Task runs only: one row per value of this task-run label. */
  public InsightsQuery groupByLabel(String key) {
    return groupBy("label:" + key);
  }

  /** Task runs only: also group by hour, day or month. */
  public InsightsQuery bucket(Bucket bucket) {
    this.bucket = bucket;
    return this;
  }

  /** Task runs only: per row, the first start to the last end of the runs sharing this label. */
  public InsightsQuery spanByLabel(String key) {
    this.spanBy = "label:" + key;
    return this;
  }

  /** Task runs only: how many slowest task runs to list, up to 100. */
  public InsightsQuery slowest(int count) {
    this.slowest = count;
    return this;
  }

  Params summaryParams() {
    return workflowParams().addAll("workflows", workflows).addAll("statuses", statuses)
        .addAll("triggers", triggers);
  }

  Params tasksParams() {
    return workflowParams()
        .addAll("workflows", workflows)
        .addAll("tasks", tasks)
        .addAll("types", types)
        .add("groupBy", groupBy)
        .add("bucket", bucket)
        .add("spanBy", spanBy)
        .add("slowest", slowest);
  }

  Params workflowParams() {
    return Params.none()
        .add("fromDate", (from != null) ? from.toEpochMilli() : null)
        .add("toDate", (to != null) ? to.toEpochMilli() : null)
        .addAll("labels", labels);
  }
}
