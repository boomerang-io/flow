package io.boomerang.kube;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.TaskRunResult;

public class TaskWatcher implements Watcher<TaskRun> {

  private static final Logger LOGGER = LogManager.getLogger(TaskWatcher.class);

  private final CountDownLatch latch;
  private Condition condition;
  private List<TaskRunResult> results = new ArrayList<>();
  private volatile boolean watchLost;

  public TaskWatcher(CountDownLatch latch) {
    this.latch = latch;
  }

  public Condition getCondition() {
    return condition;
  }

  public List<TaskRunResult> getResults() {
    return results;
  }

  /** True once the watch's connection has been lost; the caller must close and re-open it. */
  public boolean isWatchLost() {
    return watchLost;
  }

  /** Clears the lost-connection flag after the caller has re-opened the watch. */
  public void resetWatchLost() {
    watchLost = false;
  }

  @Override
  /*
   * Process the Watcher Events and return as a Condition result object
   * - Processes a delete event occurred by external source such as CLI
   *
   * Reference(s):
   * - https://tekton.dev/docs/pipelines/pipelineruns/#monitoring-execution-status
   */
  public void eventReceived(Action action, TaskRun resource) {
    LOGGER.info("Watch event received {}: {}", action.name(), resource.getMetadata().getName());
    if (resource.getStatus() == null
        || resource.getStatus().getConditions() == null
        || resource.getStatus().getConditions().isEmpty()) {
      return;
    }

    if (Action.DELETED.equals(action)
        && "Unknown".equals(resource.getStatus().getConditions().get(0).getStatus())) {
      LOGGER.info(" Task Cancelled Externally. Adjusting status");
      condition = resource.getStatus().getConditions().get(0);
      condition.setStatus("False");
      condition.setReason("TaskRunCancelled");
      condition.setMessage("The TaskRun was cancelled successfully.");
      results = resource.getStatus().getResults();
      latch.countDown();
      return;
    }

    if (Action.MODIFIED.equals(action)) {
      String modifiedMessage = resource.getStatus().getConditions().get(0).getMessage();
      if (modifiedMessage != null && !modifiedMessage.isEmpty() && modifiedMessage.contains("rpc error")) {
        LOGGER.info(" Task Failed due to RPC error");
        condition = resource.getStatus().getConditions().get(0);
        condition.setStatus("False");
        condition.setReason("TaskRunFailed");
        condition.setMessage(modifiedMessage);
        results = resource.getStatus().getResults();
        latch.countDown();
        return;
      }
    }

    evaluate(resource);
  }

  /**
   * Evaluate a TaskRun for a terminal {@code Succeeded} condition (True or False), counting the
   * latch down when found. Safe to call repeatedly on the same non-terminal TaskRun, including
   * from a reconcile poll of a listed TaskRun rather than a watch event.
   */
  public void evaluate(TaskRun resource) {
    if (resource.getStatus() == null
        || resource.getStatus().getConditions() == null
        || resource.getStatus().getConditions().isEmpty()) {
      return;
    }
    String taskStatus = resource.getStatus().getConditions().get(0).getStatus();
    LOGGER.info(
        "TaskRun Name: "
            + resource.getMetadata().getName()
            + ",\n  Start Time: "
            + resource.getStatus().getStartTime()
            + ",\n  Status: "
            + taskStatus);
    results = resource.getStatus().getResults();
    switch (taskStatus) {
      case "False":
        condition = resource.getStatus().getConditions().get(0);
        String falseMessage = condition.getMessage();
        if (falseMessage != null && !falseMessage.isEmpty() && falseMessage.contains("exited with code 1")) {
          LOGGER.info(" Task Failed. " + falseMessage);
          condition.setMessage("Task exited with error. View logs to learn more.");
        }
        latch.countDown();
        break;
      case "True":
        condition = resource.getStatus().getConditions().get(0);
        latch.countDown();
        break;
      default:
        break;
    }
  }

  /** Mark the TaskRun as terminally deleted - used when a reconcile poll finds no matching TaskRun. */
  public void markDeleted() {
    condition = new Condition();
    condition.setType("Succeeded");
    condition.setStatus("False");
    condition.setReason("JobDeleted");
    condition.setMessage("The TaskRun was deleted before completion.");
    latch.countDown();
  }

  @Override
  public void onClose(WatcherException e) {
    watchLost = true;
    LOGGER.warn("Watch closed: {}", e != null ? e.getMessage() : "no exception", e);
    // The reconcile loop notices watchLost and re-opens the watch; do not exit the process.
  }
}
