package io.boomerang.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.Watcher;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

public class JobWatcherTest {

  private static Job failedJob(String name) {
    JobCondition failed = new JobCondition();
    failed.setType("Failed");
    failed.setStatus("True");
    failed.setReason("BackoffLimitExceeded");
    return new JobBuilder()
        .withNewMetadata()
        .withName(name)
        .endMetadata()
        .withNewStatus()
        .withFailed(1)
        .withConditions(List.of(failed))
        .endStatus()
        .build();
  }

  @Test
  public void testAFinishedJobOfAnotherNameIsIgnored() {
    // Every attempt of a task carries the same labels, so the watch also delivers an earlier
    // attempt's failed Job; it must not end the attempt being watched, nor its deletion.
    Job earlierAttempt = failedJob("test-job-attempt-1");
    CountDownLatch latch = new CountDownLatch(1);
    JobWatcher watcher = new JobWatcher(latch, Duration.ofSeconds(60), "test-job-attempt-2");

    watcher.eventReceived(Watcher.Action.ADDED, earlierAttempt);
    watcher.evaluate(earlierAttempt);
    watcher.eventReceived(Watcher.Action.DELETED, earlierAttempt);

    assertEquals(1, latch.getCount());
    assertNull(watcher.getCondition());
  }

  @Test
  public void testTheWatchedJobsFailedConditionEndsTheWatch() {
    CountDownLatch latch = new CountDownLatch(1);
    JobWatcher watcher = new JobWatcher(latch, Duration.ofSeconds(60), "test-job-attempt-2");

    watcher.eventReceived(Watcher.Action.MODIFIED, failedJob("test-job-attempt-2"));

    assertEquals(0, latch.getCount());
    assertEquals("BackoffLimitExceeded", watcher.getCondition().getReason());
  }
}
