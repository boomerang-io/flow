package io.boomerang.dispatcher.sdk;

/**
 * The typed causes a dispatcher may end a task with, as the {@code statusReason} of a {@link
 * TaskFailure}. The wire carries a string, so a dispatcher may send another value; the engine
 * records one it does not know as an ordinary failure.
 */
public final class StatusReason {

  /** The dispatcher could not run the task. */
  public static final String DISPATCH_ERROR = "DispatchError";

  /** The task's work outlived its deadline. */
  public static final String DEADLINE_EXCEEDED = "DeadlineExceeded";

  /** The task's work ran and failed. */
  public static final String JOB_FAILED = "JobFailed";

  /** The task's runtime object was deleted before it completed. */
  public static final String JOB_DELETED = "JobDeleted";

  /** The task's work ran out of memory. */
  public static final String OOM_KILLED = "OOMKilled";

  /** The task's image could not be pulled. */
  public static final String IMAGE_PULL = "ImagePull";

  /** The task declared an image the dispatcher does not allow. */
  public static final String IMAGE_NOT_ALLOWED = "ImageNotAllowed";

  /** An admission controller refused the task's runtime object. */
  public static final String ADMISSION_DENIED = "AdmissionDenied";

  /** The task's results were over the size the dispatcher accepts. */
  public static final String RESULTS_TOO_LARGE = "ResultsTooLarge";

  /** The runtime refused the task's work for lack of capacity; the engine requeues it. */
  public static final String EXCEEDED_QUOTA = "ExceededQuota";

  /** The task's work never began within the start deadline; the engine requeues it. */
  public static final String START_TIMEOUT = "StartTimeout";

  /** The runtime or a gateway behind it refused the task for rate; the engine requeues it. */
  public static final String RATE_LIMITED = "RateLimited";

  private StatusReason() {}
}
