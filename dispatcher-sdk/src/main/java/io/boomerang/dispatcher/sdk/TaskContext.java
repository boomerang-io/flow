package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * What a running {@link TaskHandler} is given beside the task: whether the engine has since
 * ordered the task terminated, and the artifact transfer for the upload and download task types.
 * The SDK creates one per task run; a handler never needs to.
 */
public final class TaskContext {

  private static final Log LOGGER = LogFactory.getLog(TaskContext.class);

  private final TaskRun task;
  private final String dispatcherId;
  private final ArtifactTransfer artifacts;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final List<Runnable> cancelActions = new CopyOnWriteArrayList<>();

  public TaskContext(TaskRun task, String dispatcherId, ArtifactTransfer artifacts) {
    this.task = task;
    this.dispatcherId = dispatcherId;
    this.artifacts = artifacts;
  }

  public TaskRun task() {
    return task;
  }

  /** The id this dispatcher registered under. */
  public String dispatcherId() {
    return dispatcherId;
  }

  /**
   * Whether the engine has ordered this task terminated - cancelled, timed out or requeued. A
   * handler that sees it SHOULD stop and return; its end is not reported.
   */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * Run {@code action} when the engine orders this task terminated, or at once when it already
   * has. Actions run on the thread that delivers the order, never on the handler's.
   */
  public void onCancel(Runnable action) {
    cancelActions.add(action);
    if (cancelled.get() && cancelActions.remove(action)) {
      runQuietly(action);
    }
  }

  /** The transfer an {@code uploadartifact} or {@code downloadartifact} task performs. */
  public ArtifactTransfer artifacts() {
    return artifacts;
  }

  // Each action runs exactly once: whichever of cancel and onCancel removes it, runs it.
  void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      for (Runnable action : cancelActions) {
        if (cancelActions.remove(action)) {
          runQuietly(action);
        }
      }
    }
  }

  private void runQuietly(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      LOGGER.warn("TaskRun (" + task.getId() + ") cancel action failed: " + e.getMessage(), e);
    }
  }
}
