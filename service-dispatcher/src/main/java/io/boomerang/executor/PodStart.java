package io.boomerang.executor;

/**
 * How far a Task's pod has got towards running. {@code waitingReason} is the reason a container is
 * held in the waiting state (for example {@code ImagePullBackOff}); {@code message} says why the
 * pod is not running, for the report.
 */
public record PodStart(boolean started, String waitingReason, String message) {

  public static PodStart running() {
    return new PodStart(true, null, null);
  }

  public static PodStart notCreated() {
    return new PodStart(false, null, "no pod was created");
  }

  public static PodStart pending(String waitingReason, String message) {
    return new PodStart(false, waitingReason, message);
  }
}
