package io.boomerang.kube;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.fabric8.knative.internal.pkg.apis.Condition;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.fabric8.tekton.pipeline.v1beta1.TaskRunResult;

public class TaskWatcher implements Watcher<TaskRun> {

  private static final Logger LOGGER = LogManager.getLogger(TaskWatcher.class);

  private final CountDownLatch latch;
  private Condition condition;
  private List<TaskRunResult> results;

  public TaskWatcher(CountDownLatch latch) {
    this.latch = latch;
  }

  public Condition getCondition() {
    return condition;
  }

  public List<TaskRunResult> getResults() {
    return results;
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
    if (resource.getStatus() != null
        && resource.getStatus().getConditions() != null
        && !resource.getStatus().getConditions().isEmpty()) {
      String taskStatus = resource.getStatus().getConditions().get(0).getStatus();
      LOGGER.info(
          "TaskRun Name: "
              + resource.getMetadata().getName()
              + ",\n  Start Time: "
              + resource.getStatus().getStartTime()
              + ",\n  Status: "
              + taskStatus);
      for (Condition condition : resource.getStatus().getConditions()) {
        LOGGER.info(" TaskRun Condition: " + condition.toString());
      }
      results = resource.getStatus().getTaskResults();
      LOGGER.info(" TaskRun Results: " + results.toString());
      switch (action.name()) {
        case "DELETED":
          if ("Unknown".equals(resource.getStatus().getConditions().get(0).getStatus())) {
            LOGGER.info(" Task Cancelled Externally. Adjusting status");
            condition = resource.getStatus().getConditions().get(0);
            condition.setStatus("False");
            condition.setReason("TaskRunCancelled");
            condition.setMessage("The TaskRun was cancelled successfully.");
            latch.countDown();
          }
        case "MODIFIED":
          if (resource.getStatus().getConditions() != null
              && !resource.getStatus().getConditions().get(0).getMessage().isEmpty()
              && resource.getStatus().getConditions().get(0).getMessage().contains("rpc error")) {
            LOGGER.info(" Task Failed due to RPC error");
            condition = resource.getStatus().getConditions().get(0);
            condition.setStatus("False");
            condition.setReason("TaskRunFailed");
            condition.setMessage(resource.getStatus().getConditions().get(0).getMessage());
            latch.countDown();
            //             TODO need to have the task cancelled.
          }
      }
      switch (taskStatus) {
        case "False":
          condition = resource.getStatus().getConditions().get(0);
          if (!resource.getStatus().getConditions().get(0).getMessage().isEmpty()
              && resource
                  .getStatus()
                  .getConditions()
                  .get(0)
                  .getMessage()
                  .contains("exited with code 1")) {
            LOGGER.info(
                " Task Failed. " + resource.getStatus().getConditions().get(0).getMessage());
            condition.setMessage("Task exited with error. View logs to learn more.");
          }
          latch.countDown();
        case "True":
          condition = resource.getStatus().getConditions().get(0);
          latch.countDown();
      }
    }
  }

  @Override
  public void onClose(WatcherException e) {
    LOGGER.error("Watch error received: {}", e.getMessage(), e);
    // Cause the pod to restart
    System.exit(1);
  }
}
