package io.boomerang.executor;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Watches a batch/v1 Job to completion. Counts the latch down once the Job reaches a terminal
 * state - a {@code Failed}/{@code Complete} condition (checked first, since it's the only place
 * a reason such as {@code DeadlineExceeded} or {@code BackoffLimitExceeded} is reported), {@code
 * status.succeeded >= 1}, or the Job being deleted out from under the watch. The Job controller
 * writes {@code status.failed} before it writes the {@code Failed} condition, so {@code
 * status.failed >= 1} alone is NOT terminal - it only becomes terminal (falling back to a generic
 * {@code JobFailed}) once {@link #failedConditionGrace} has elapsed with still no condition.
 */
public class JobWatcher implements Watcher<Job> {

  private static final Logger LOGGER = LogManager.getLogger(JobWatcher.class);

  private final CountDownLatch latch;
  private final Duration failedConditionGrace;
  private JobCondition condition;
  private volatile boolean watchLost;
  private Instant failedObservedAt;

  public JobWatcher(CountDownLatch latch, Duration failedConditionGrace) {
    this.latch = latch;
    this.failedConditionGrace = failedConditionGrace;
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
   * Evaluate a Job for a terminal state, counting the latch down when found. Safe to call
   * repeatedly on the same non-terminal Job, including from a reconcile poll of a listed Job
   * rather than a watch event.
   *
   * <p>A true {@code Failed}/{@code Complete} condition is checked first and always wins - it
   * carries the real reason ({@code DeadlineExceeded}, {@code BackoffLimitExceeded}, ...). Only
   * once no such condition exists do the bare counts decide: {@code status.succeeded >= 1} is
   * terminal immediately, but {@code status.failed >= 1} is NOT - the Job controller writes that
   * count before it writes the condition, so this method keeps waiting (returning without
   * counting the latch down) until either the condition shows up or {@link #failedConditionGrace}
   * elapses, at which point it falls back to a generic {@code JobFailed} so a Job whose
   * controller never writes the condition cannot hang the watch forever.
   */
  public void evaluate(Job job) {
    JobStatus status = job.getStatus();
    if (status == null) {
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
    if (status.getSucceeded() != null && status.getSucceeded() >= 1) {
      condition = terminalCondition("Complete", "True", "JobComplete", "The Job completed successfully.");
      latch.countDown();
      return;
    }
    if (status.getFailed() != null && status.getFailed() >= 1) {
      if (failedObservedAt == null) {
        failedObservedAt = Instant.now();
      }
      if (Duration.between(failedObservedAt, Instant.now()).compareTo(failedConditionGrace) >= 0) {
        condition = terminalCondition("Failed", "True", "JobFailed", "The Job's Pod failed to complete.");
        latch.countDown();
      }
      // else: still waiting on the controller to write the Failed condition; re-evaluated on the
      // next watch event or reconcile poll.
    }
  }

  /** Mark the Job as terminally deleted - used when a reconcile poll finds no matching Job. */
  public void markDeleted() {
    condition = terminalCondition("Failed", "False", "JobDeleted", "The Job was deleted before completion.");
    latch.countDown();
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
