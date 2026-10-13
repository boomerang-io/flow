package io.boomerang.dispatcher.sdk;

import java.util.List;

/**
 * Which task runs one task poll claims. Filters are ANDed and the values within one are ORed;
 * {@code *} is the only wildcard. With no filter a poll claims every type the dispatcher
 * registered. Termination orders follow the same filter.
 */
public final class TaskFilter {

  private static final TaskFilter NONE = new TaskFilter(List.of(), List.of(), null);

  private final List<String> types;
  private final List<String> tasks;
  private final String workflowLabel;

  private TaskFilter(List<String> types, List<String> tasks, String workflowLabel) {
    this.types = types;
    this.tasks = tasks;
    this.workflowLabel = workflowLabel;
  }

  /** Return the filter that claims every registered type. */
  public static TaskFilter none() {
    return NONE;
  }

  /** Return this filter limited to the given task types, a subset of those registered. */
  public TaskFilter types(String... types) {
    return new TaskFilter(List.of(types), tasks, workflowLabel);
  }

  /** Return this filter limited to the given task slugs. */
  public TaskFilter tasks(String... slugs) {
    return new TaskFilter(types, List.of(slugs), workflowLabel);
  }

  /** Return this filter limited to workflows carrying label {@code key} with any of the values. */
  public TaskFilter workflowLabel(String key, String... values) {
    return new TaskFilter(types, tasks, key + "=" + String.join(",", values));
  }

  public List<String> getTypes() {
    return types;
  }

  public List<String> getTasks() {
    return tasks;
  }

  /** The label filter as sent, {@code key=value[,value]}, or null for none. */
  public String getWorkflowLabel() {
    return workflowLabel;
  }

  @Override
  public String toString() {
    return "TaskFilter[types=" + types + ", tasks=" + tasks + ", workflowLabel=" + workflowLabel + "]";
  }
}
