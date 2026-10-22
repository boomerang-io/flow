package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.RunPhase;
import io.boomerang.flow.sdk.model.RunStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What {@link TaskRuns#query} narrows by, mapped to the route's parameters. The route requires
 * {@link #labels} or {@link #runs}; a workflow run's labels are on each of its task runs, and a
 * child run has its parent's.
 *
 * <pre>{@code
 * TaskRunQuery.all().label("team.io/batch", "b-7").tasks("review").order(Order.DESC)
 * }</pre>
 */
public final class TaskRunQuery {

  private final List<String> labels = new ArrayList<>();
  private final List<RunStatus> statuses = new ArrayList<>();
  private final List<RunPhase> phases = new ArrayList<>();
  private final List<String> runs = new ArrayList<>();
  private final List<String> workflows = new ArrayList<>();
  private final List<String> tasks = new ArrayList<>();
  private Instant from;
  private Instant to;
  private Integer page;
  private Integer limit;
  private Order order;

  private TaskRunQuery() {}

  public static TaskRunQuery all() {
    return new TaskRunQuery();
  }

  /** Task runs whose run carries every one of these labels, each as {@code key=value}. */
  public TaskRunQuery labels(String... labels) {
    this.labels.addAll(Arrays.asList(labels));
    return this;
  }

  public TaskRunQuery label(String key, String value) {
    return labels(key + "=" + value);
  }

  public TaskRunQuery statuses(RunStatus... statuses) {
    this.statuses.addAll(Arrays.asList(statuses));
    return this;
  }

  public TaskRunQuery phases(RunPhase... phases) {
    this.phases.addAll(Arrays.asList(phases));
    return this;
  }

  /** Task runs of these workflow runs, by id. */
  public TaskRunQuery runs(String... workflowRunIds) {
    this.runs.addAll(Arrays.asList(workflowRunIds));
    return this;
  }

  /** Task runs of these workflows, by id. */
  public TaskRunQuery workflows(String... ids) {
    this.workflows.addAll(Arrays.asList(ids));
    return this;
  }

  /** Task runs with these task names; a name also matches its for-each items. */
  public TaskRunQuery tasks(String... names) {
    this.tasks.addAll(Arrays.asList(names));
    return this;
  }

  public TaskRunQuery from(Instant from) {
    this.from = from;
    return this;
  }

  public TaskRunQuery to(Instant to) {
    this.to = to;
    return this;
  }

  public TaskRunQuery page(int page, int limit) {
    this.page = page;
    this.limit = limit;
    return this;
  }

  public TaskRunQuery order(Order order) {
    this.order = order;
    return this;
  }

  TaskRunQuery withPage(int page) {
    TaskRunQuery copy = all();
    copy.labels.addAll(labels);
    copy.statuses.addAll(statuses);
    copy.phases.addAll(phases);
    copy.runs.addAll(runs);
    copy.workflows.addAll(workflows);
    copy.tasks.addAll(tasks);
    copy.from = from;
    copy.to = to;
    copy.page = page;
    copy.limit = limit;
    copy.order = order;
    return copy;
  }

  Params params() {
    return Params.none()
        .addAll("labels", labels)
        .addAll("statuses", statuses)
        .addAll("phase", phases)
        .addAll("workflowruns", runs)
        .addAll("workflows", workflows)
        .addAll("tasks", tasks)
        .add("fromDate", (from != null) ? from.toEpochMilli() : null)
        .add("toDate", (to != null) ? to.toEpochMilli() : null)
        .add("page", page)
        .add("limit", limit)
        .add("order", order);
  }
}
