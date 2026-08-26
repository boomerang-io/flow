package io.boomerang.executor;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Watches a batch/v1 Job to completion. Counts the latch down as soon as the Job reaches a
 * terminal state - {@code status.succeeded >= 1}, {@code status.failed >= 1}, a {@code
 * Failed}/{@code Complete} condition, or the Job being deleted out from under the watch.
 */
public class JobWatcher implements Watcher<Job> {

  private static final Logger LOGGER = LogManager.getLogger(JobWatcher.class);

  private final CountDownLatch latch;
  private JobCondition condition;

  public JobWatcher(CountDownLatch latch) {
    this.latch = latch;
  }

  public JobCondition getCondition() {
    return condition;
  }

  @Override
  public void eventReceived(Action action, Job resource) {
    LOGGER.info("Watch event received {}: {}", action.name(), resource.getMetadata().getName());
    JobStatus status = resource.getStatus();

    if (Action.DELETED.equals(action)) {
      condition = terminalCondition("Failed", "False", "JobDeleted", "The Job was deleted before completion.");
      latch.countDown();
      return;
    }

    if (status == null) {
      return;
    }
    if (status.getSucceeded() != null && status.getSucceeded() >= 1) {
      condition = terminalCondition("Complete", "True", "JobComplete", "The Job completed successfully.");
      latch.countDown();
      return;
    }
    if (status.getFailed() != null && status.getFailed() >= 1) {
      condition = failedConditionFrom(status);
      latch.countDown();
      return;
    }
    if (status.getConditions() != null) {
      for (JobCondition candidate : status.getConditions()) {
        if (("Failed".equals(candidate.getType()) || "Complete".equals(candidate.getType()))
            && "True".equals(candidate.getStatus())) {
          condition = candidate;
          latch.countDown();
          return;
        }
      }
    }
  }

  private JobCondition failedConditionFrom(JobStatus status) {
    if (status.getConditions() != null) {
      for (JobCondition candidate : status.getConditions()) {
        if ("Failed".equals(candidate.getType())) {
          return candidate;
        }
      }
    }
    return terminalCondition("Failed", "True", "JobFailed", "The Job's Pod failed to complete.");
  }

  private JobCondition terminalCondition(String type, String status, String reason, String message) {
    JobCondition condition = new JobCondition();
    condition.setType(type);
    condition.setStatus(status);
    condition.setReason(reason);
    condition.setMessage(message);
    return condition;
  }

  @Override
  public void onClose(WatcherException e) {
    LOGGER.error("Watch error received: {}", e.getMessage(), e);
    // The caller's latch.await() times out and surfaces a TASK_EXECUTION_ERROR; do not exit the process.
  }
}
