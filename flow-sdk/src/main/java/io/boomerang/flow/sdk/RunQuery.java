package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.RunPhase;
import io.boomerang.flow.sdk.model.RunStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What {@link Runs#query} and {@link Runs#count} narrow by, mapped to the route's parameters.
 * Every filter must match; none matches every run. Paging is {@code page} from 0 and {@code
 * limit}; without a limit the server returns every match as one page.
 *
 * <pre>{@code
 * RunQuery.all().label("team.io/analysis", "x").statuses(RunStatus.running).page(0, 50)
 * }</pre>
 */
public final class RunQuery {

  private final List<String> labels = new ArrayList<>();
  private final List<RunStatus> statuses = new ArrayList<>();
  private final List<RunPhase> phases = new ArrayList<>();
  private final List<String> runs = new ArrayList<>();
  private final List<String> workflows = new ArrayList<>();
  private final List<String> triggers = new ArrayList<>();
  private Instant from;
  private Instant to;
  private Integer page;
  private Integer limit;
  private Order order;

  private RunQuery() {}

  /** No filter: every run of the workspace. */
  public static RunQuery all() {
    return new RunQuery();
  }

  /** Runs carrying every one of these labels, each as {@code key=value}. */
  public RunQuery labels(String... labels) {
    this.labels.addAll(Arrays.asList(labels));
    return this;
  }

  /** Runs carrying this label. */
  public RunQuery label(String key, String value) {
    return labels(key + "=" + value);
  }

  public RunQuery statuses(RunStatus... statuses) {
    this.statuses.addAll(Arrays.asList(statuses));
    return this;
  }

  public RunQuery phases(RunPhase... phases) {
    this.phases.addAll(Arrays.asList(phases));
    return this;
  }

  /** Runs with these ids. */
  public RunQuery runs(String... ids) {
    this.runs.addAll(Arrays.asList(ids));
    return this;
  }

  /** Runs of these workflows, by id. */
  public RunQuery workflows(String... ids) {
    this.workflows.addAll(Arrays.asList(ids));
    return this;
  }

  /** Runs started by these triggers, such as {@code manual} or {@code schedule}. */
  public RunQuery triggers(String... triggers) {
    this.triggers.addAll(Arrays.asList(triggers));
    return this;
  }

  /** Runs created at or after this instant. */
  public RunQuery from(Instant from) {
    this.from = from;
    return this;
  }

  /** Runs created at or before this instant. */
  public RunQuery to(Instant to) {
    this.to = to;
    return this;
  }

  /** The page to read, from 0, of {@code limit} runs each. */
  public RunQuery page(int page, int limit) {
    this.page = page;
    this.limit = limit;
    return this;
  }

  /** The sort order on creation date; the server's default is ascending. */
  public RunQuery order(Order order) {
    this.order = order;
    return this;
  }

  RunQuery withPage(int page) {
    RunQuery copy = all();
    copy.labels.addAll(labels);
    copy.statuses.addAll(statuses);
    copy.phases.addAll(phases);
    copy.runs.addAll(runs);
    copy.workflows.addAll(workflows);
    copy.triggers.addAll(triggers);
    copy.from = from;
    copy.to = to;
    copy.page = page;
    copy.limit = limit;
    copy.order = order;
    return copy;
  }

  Params params() {
    return countParams()
        .addAll("statuses", statuses)
        .addAll("phase", phases)
        .addAll("workflowruns", runs)
        .addAll("triggers", triggers)
        .add("page", page)
        .add("limit", limit)
        .add("order", order);
  }

  Params countParams() {
    return Params.none()
        .addAll("labels", labels)
        .addAll("workflows", workflows)
        .add("fromDate", (from != null) ? from.toEpochMilli() : null)
        .add("toDate", (to != null) ? to.toEpochMilli() : null);
  }
}
