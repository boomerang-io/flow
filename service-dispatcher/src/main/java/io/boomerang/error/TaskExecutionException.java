package io.boomerang.error;

import io.boomerang.common.model.RunResult;
import java.util.List;

/**
 * A TASK_EXECUTION_ERROR that carries both the typed {@code statusReason} the engine records on
 * {@code TaskRunEndRequest} (closed set: DeadlineExceeded, JobDeleted, JobFailed, OOMKilled,
 * ImagePull, AdmissionDenied, ResultsTooLarge, DispatchError) and the Result Parameters the Task
 * wrote before it failed. A non-zero exit does not erase output the Task already wrote to its
 * results channel (the Tekton termination message or the Kubernetes Job termination log).
 */
public class TaskExecutionException extends BoomerangException {

  private static final long serialVersionUID = 1L;

  private final String statusReason;

  private final List<RunResult> results;

  private final String message;

  public TaskExecutionException(String statusReason, String message) {
    this(statusReason, List.of(), message);
  }

  public TaskExecutionException(String statusReason, List<RunResult> results, String message) {
    super(BoomerangError.TASK_EXECUTION_ERROR, message);
    this.statusReason = statusReason;
    this.results = results != null ? results : List.of();
    this.message = message;
  }

  /** Results-only form: a generic Job failure whose cause is not typed further. */
  public TaskExecutionException(List<RunResult> results, String message) {
    this("JobFailed", results, message);
  }

  public String getStatusReason() {
    return statusReason;
  }

  public List<RunResult> getResults() {
    return results;
  }

  @Override
  public String getMessage() {
    return message;
  }
}
