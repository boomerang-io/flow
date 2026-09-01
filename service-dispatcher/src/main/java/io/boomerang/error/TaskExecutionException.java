package io.boomerang.error;

import io.boomerang.common.model.RunResult;
import java.util.List;

/**
 * A TASK_EXECUTION_ERROR that carries the Result Parameters a Task wrote before it failed. A
 * non-zero exit does not erase output the Task already wrote to its results channel (the Tekton
 * termination message or the Kubernetes Job termination log).
 */
public class TaskExecutionException extends BoomerangException {

  private static final long serialVersionUID = 1L;

  private final List<RunResult> results;

  public TaskExecutionException(List<RunResult> results, Object... args) {
    super(BoomerangError.TASK_EXECUTION_ERROR, args);
    this.results = results != null ? results : List.of();
  }

  public List<RunResult> getResults() {
    return results;
  }
}
