package io.boomerang.executor;

import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;

/**
 * A Task execution failure carrying the typed {@code statusReason} the engine records on {@code
 * TaskRunEndRequest}, alongside the human-readable message. {@code statusReason} MUST be one of
 * the closed set documented on {@code TaskRunEndRequest.statusReason}: DeadlineExceeded,
 * JobDeleted, JobFailed, OOMKilled, ImagePull, AdmissionDenied, ResultsTooLarge, DispatchError.
 */
public class TaskExecutionException extends BoomerangException {

  private static final long serialVersionUID = 1L;

  private final String statusReason;

  private final String message;

  public TaskExecutionException(String statusReason, String message) {
    super(BoomerangError.TASK_EXECUTION_ERROR, message);
    this.statusReason = statusReason;
    this.message = message;
  }

  public String getStatusReason() {
    return statusReason;
  }

  @Override
  public String getMessage() {
    return message;
  }
}
