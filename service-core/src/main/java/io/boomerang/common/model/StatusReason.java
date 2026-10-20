package io.boomerang.common.model;

import java.util.Set;

/**
 * The typed causes a task run ends with, in {@code statusReason}: the ones a dispatcher sends on
 * end and the ones the engine writes itself. The wire carries a string, and a reason not listed
 * here is recorded as an ordinary failure. The dispatcher SDK's own {@code StatusReason} must stay
 * a subset of this set, which its tests check.
 */
public abstract class StatusReason {

  // Sent by a dispatcher.
  public static final String DISPATCH_ERROR = "DispatchError";
  public static final String DEADLINE_EXCEEDED = "DeadlineExceeded";
  public static final String JOB_FAILED = "JobFailed";
  public static final String JOB_DELETED = "JobDeleted";
  public static final String OOM_KILLED = "OOMKilled";
  public static final String IMAGE_PULL = "ImagePull";
  public static final String IMAGE_NOT_ALLOWED = "ImageNotAllowed";
  public static final String ADMISSION_DENIED = "AdmissionDenied";
  public static final String RESULTS_TOO_LARGE = "ResultsTooLarge";

  // Sent by a dispatcher when the task never started: the engine requeues it while its retry
  // budget lasts instead of failing it.
  public static final String EXCEEDED_QUOTA = "ExceededQuota";
  public static final String START_TIMEOUT = "StartTimeout";
  public static final String RATE_LIMITED = "RateLimited";

  // Written by the engine.
  public static final String DISPATCH_PREPARE_FAILED = "DispatchPrepareFailed";
  public static final String SPEC_FILL_FAILED = "SpecFillFailed";
  public static final String DISPATCHER_GONE = "DispatcherGone";
  public static final String LEASE_EXPIRED = "LeaseExpired";
  public static final String CHILD_RUN_FAILED = "ChildRunFailed";
  public static final String NESTING_DEPTH_EXCEEDED = "NestingDepthExceeded";
  public static final String ITEM_FAILED = "ItemFailed";
  public static final String FOREACH_ITEMS_INVALID = "ForeachItemsInvalid";
  public static final String FOREACH_TOO_MANY_ITEMS = "ForeachTooManyItems";
  public static final String PARAM_UNRESOLVED = "ParamUnresolved";
  public static final String ARTIFACT_NOT_FOUND = "ArtifactNotFound";
  public static final String ARTIFACT_REFUSED = "ArtifactRefused";

  /** The reasons that mean the task never started, so it is requeued rather than failed. */
  public static final Set<String> REQUEUEABLE = Set.of(EXCEEDED_QUOTA, START_TIMEOUT, RATE_LIMITED);

  private StatusReason() {}
}
