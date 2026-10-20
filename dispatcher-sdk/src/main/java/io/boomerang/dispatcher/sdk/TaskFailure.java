package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.RunResult;
import java.util.List;

/**
 * A task that failed, with a typed reason and any results it wrote before failing. Thrown by a
 * {@link TaskHandler}; any other exception a handler throws ends the task as {@value
 * #DISPATCH_ERROR}.
 *
 * <p>The engine treats three reasons as "never started" and requeues the task instead of failing
 * it, on a budget of three more attempts with backoff: use {@link #exceededQuota} when the runtime
 * refused the work for lack of capacity, {@link #startTimeout} when the work was created but never
 * began within the dispatcher's start deadline, and {@link #rateLimited} when the runtime or a
 * gateway behind it refused the task for rate. The other reasons a dispatcher sends are in {@link
 * StatusReason}; one the engine does not know is an ordinary failure.
 */
public class TaskFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The same as {@link StatusReason#DISPATCH_ERROR}. */
  public static final String DISPATCH_ERROR = StatusReason.DISPATCH_ERROR;

  /** The same as {@link StatusReason#EXCEEDED_QUOTA}. */
  public static final String EXCEEDED_QUOTA = StatusReason.EXCEEDED_QUOTA;

  /** The same as {@link StatusReason#START_TIMEOUT}. */
  public static final String START_TIMEOUT = StatusReason.START_TIMEOUT;

  /** The same as {@link StatusReason#RATE_LIMITED}. */
  public static final String RATE_LIMITED = StatusReason.RATE_LIMITED;

  private final String statusReason;

  private final transient List<RunResult> results;

  public TaskFailure(String statusReason, String message) {
    this(statusReason, message, List.of());
  }

  public TaskFailure(String statusReason, String message, List<RunResult> results) {
    super(message);
    this.statusReason = statusReason;
    this.results = (results != null) ? List.copyOf(results) : List.of();
  }

  public TaskFailure(String statusReason, String message, Throwable cause) {
    super(message, cause);
    this.statusReason = statusReason;
    this.results = List.of();
  }

  /** Return a failure that tells the engine the runtime had no capacity for the task. */
  public static TaskFailure exceededQuota(String message) {
    return new TaskFailure(EXCEEDED_QUOTA, message);
  }

  /** Return a failure that tells the engine the task's work never began in time. */
  public static TaskFailure startTimeout(String message) {
    return new TaskFailure(START_TIMEOUT, message);
  }

  /** Return a failure that tells the engine the task was refused for rate and should be retried. */
  public static TaskFailure rateLimited(String message) {
    return new TaskFailure(RATE_LIMITED, message);
  }

  public String getStatusReason() {
    return statusReason;
  }

  /** The results the task wrote before it failed; reported with the failure. */
  public List<RunResult> getResults() {
    return results;
  }
}
