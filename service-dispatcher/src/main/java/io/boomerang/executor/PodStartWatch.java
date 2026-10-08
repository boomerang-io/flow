package io.boomerang.executor;

import io.boomerang.error.TaskExecutionException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Bounds how long a Task may take to START, apart from how long it may run. A container held on an
 * image it cannot pull, or one that cannot be created, fails the Task once that has lasted {@code
 * failureGrace}; those are mistakes in the Task, so they are not retried. A pod still not running
 * at {@code startDeadline} - unschedulable, held by quota, or never created - is reported as {@code
 * StartTimeout}, which the engine requeues for another attempt. Once the pod has started neither
 * applies again. A zero start deadline turns that check off.
 */
public class PodStartWatch {

  static final Set<String> IMAGE_FAILURES =
      Set.of("ErrImagePull", "ImagePullBackOff", "InvalidImageName", "ImageInspectError");

  static final Set<String> CONTAINER_FAILURES =
      Set.of("CreateContainerConfigError", "CreateContainerError");

  private final Instant createdAt;
  private final Duration startDeadline;
  private final Duration failureGrace;
  private boolean started;
  private Instant failingSince;

  public PodStartWatch(Instant createdAt, Duration startDeadline, Duration failureGrace) {
    this.createdAt = createdAt;
    this.startDeadline = startDeadline;
    this.failureGrace = failureGrace;
  }

  /** Whether the pod has been seen running; after that there is nothing left to check. */
  public boolean hasStarted() {
    return started;
  }

  /** Return the failure to report for this look at the pod, or null to keep waiting. */
  public TaskExecutionException check(PodStart pod, Instant now) {
    if (started || pod.started()) {
      started = true;
      return null;
    }
    String reason = pod.waitingReason();
    boolean imageFailure = reason != null && IMAGE_FAILURES.contains(reason);
    if (imageFailure || (reason != null && CONTAINER_FAILURES.contains(reason))) {
      // Kubernetes alternates ErrImagePull and ImagePullBackOff, so the grace runs from the first
      // failure seen, not from the latest reason.
      failingSince = (failingSince == null) ? now : failingSince;
      if (!now.isBefore(failingSince.plus(failureGrace))) {
        return new TaskExecutionException(
            imageFailure ? "ImagePull" : "DispatchError", reason + " - " + pod.message());
      }
    } else {
      failingSince = null;
    }
    if (!startDeadline.isZero() && !now.isBefore(createdAt.plus(startDeadline))) {
      return new TaskExecutionException(
          "StartTimeout",
          "StartTimeout - The Task did not start within "
              + startDeadline.toMinutes()
              + " minutes: "
              + pod.message());
    }
    return null;
  }
}
