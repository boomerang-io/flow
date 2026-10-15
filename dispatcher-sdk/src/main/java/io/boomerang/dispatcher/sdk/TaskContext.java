package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * What a running {@link TaskHandler} is given beside the task: whether the engine has since
 * ordered the task terminated, the artifact transfer for the upload and download task types, and
 * a way to store the task's finished log. The SDK creates one per task run; a handler never needs
 * to.
 */
public final class TaskContext {

  private static final Log LOGGER = LogFactory.getLog(TaskContext.class);

  private final TaskRun task;
  private final String dispatcherId;
  private final ArtifactTransfer artifacts;
  private final LogUploader logs;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final List<Runnable> cancelActions = new CopyOnWriteArrayList<>();

  public TaskContext(TaskRun task, String dispatcherId, ArtifactTransfer artifacts) {
    this(task, dispatcherId, artifacts, null);
  }

  TaskContext(TaskRun task, String dispatcherId, ArtifactTransfer artifacts, LogUploader logs) {
    this.task = task;
    this.dispatcherId = dispatcherId;
    this.artifacts = artifacts;
    this.logs = logs;
  }

  /**
   * Store the task's finished log with the engine, so it is served from there and outlives
   * whatever ran the task. Call it before returning from {@link TaskHandler#run}: the end is
   * reported after, so the log is in place by the time the task shows as finished. Best effort -
   * a failure is logged and never fails the task. Returns whether the engine stored it; false
   * when the engine keeps no logs (no artifact store), when the log is over its limit, or when
   * this context was created without an engine.
   */
  public boolean uploadLog(byte[] log) {
    return logs != null && logs.upload(task, log);
  }

  /** {@link #uploadLog(byte[])} for a log held as text, sent as UTF-8. */
  public boolean uploadLog(String log) {
    return log != null && uploadLog(log.getBytes(StandardCharsets.UTF_8));
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
