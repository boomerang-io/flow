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
  private volatile boolean watchLost;

  public JobWatcher(CountDownLatch latch) {
    this.latch = latch;
  }

  public JobCondition getCondition() {
    return condition;
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
  public void eventReceived(Action action, Job resource) {
    LOGGER.info("Watch event received {}: {}", action.name(), resource.getMetadata().getName());
    if (Action.DELETED.equals(action)) {
      markDeleted();
      return;
    }
    evaluate(resource);
  }

  /**
   * Evaluate a Job for a terminal state - {@code status.succeeded >= 1}, {@code status.failed >=
   * 1}, or a terminal {@code Failed}/{@code Complete} condition - counting the latch down when
   * found. Safe to call repeatedly on the same non-terminal Job, including from a reconcile poll
   * of a listed Job rather than a watch event.
   */
  public void evaluate(Job job) {
    JobStatus status = job.getStatus();
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

  /** Mark the Job as terminally deleted - used when a reconcile poll finds no matching Job. */
  public void markDeleted() {
    condition = terminalCondition("Failed", "False", "JobDeleted", "The Job was deleted before completion.");
    latch.countDown();
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
    watchLost = true;
    LOGGER.warn("Watch closed: {}", e != null ? e.getMessage() : "no exception", e);
    // The reconcile loop notices watchLost and re-opens the watch; do not exit the process.
  }
}
