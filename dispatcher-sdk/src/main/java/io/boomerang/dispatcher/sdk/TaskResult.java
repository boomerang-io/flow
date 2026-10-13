package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.RunResult;
import java.util.List;

/** A task that succeeded: the results it produced and an optional status message. */
public final class TaskResult {

  private static final TaskResult EMPTY = new TaskResult(List.of(), null);

  private final List<RunResult> results;
  private final String message;

  private TaskResult(List<RunResult> results, String message) {
    this.results = (results != null) ? List.copyOf(results) : List.of();
    this.message = message;
  }

  /** Return a success with no results. */
  public static TaskResult empty() {
    return EMPTY;
  }

  /** Return a success carrying {@code results}. */
  public static TaskResult of(List<RunResult> results) {
    return new TaskResult(results, null);
  }

  /** Return a success carrying {@code results} and a status message for the run's record. */
  public static TaskResult of(List<RunResult> results, String message) {
    return new TaskResult(results, message);
  }

  public List<RunResult> getResults() {
    return results;
  }

  /** The status message, or null for none. */
  public String getMessage() {
    return message;
  }
}
