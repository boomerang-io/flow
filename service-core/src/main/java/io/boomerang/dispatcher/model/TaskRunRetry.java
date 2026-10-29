package io.boomerang.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.model.RunRetry;

/**
 * The node's retry budget as the engine holds it when a task is handed out: {@code max} is the
 * node's {@code retry.max}, absent when the node asked for no retry, and {@code failures} how many
 * reported failures the engine has already retried. The engine's own requeues of an attempt that
 * never ran are not in it. A dispatcher reads {@code failures + 1 >= max} as the last attempt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRunRetry(Integer max, int failures) {

  /** The wire form of a task run's retry state; null when the engine holds none. */
  public static TaskRunRetry of(RunRetry retry) {
    return (retry != null) ? new TaskRunRetry(retry.getMax(), retry.getFailures()) : null;
  }
}
