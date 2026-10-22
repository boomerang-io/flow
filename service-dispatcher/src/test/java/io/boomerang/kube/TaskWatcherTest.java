package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.TaskRunBuilder;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

public class TaskWatcherTest {

  @Test
  public void testModifiedWithSucceededTrueCountsDownAndSetsCondition() {
    Condition succeeded = new Condition();
    succeeded.setType("Succeeded");
    succeeded.setStatus("True");

    TaskRun taskRun =
        new TaskRunBuilder()
            .withNewMetadata()
            .withName("test-taskrun")
            .endMetadata()
            .withNewStatus()
            .withConditions(succeeded)
            .withResults(List.of())
            .endStatus()
            .build();

    CountDownLatch latch = new CountDownLatch(1);
    TaskWatcher watcher = new TaskWatcher(latch, "test-taskrun");

    watcher.eventReceived(Watcher.Action.MODIFIED, taskRun);

    assertEquals(0, latch.getCount());
    assertNotNull(watcher.getCondition());
    assertEquals("True", watcher.getCondition().getStatus());
  }

  @Test
  public void testModifiedWithSucceededFalseAndNullMessageDoesNotThrow() {
    Condition failed = new Condition();
    failed.setType("Succeeded");
    failed.setStatus("False");
    failed.setMessage(null);

    TaskRun taskRun =
        new TaskRunBuilder()
            .withNewMetadata()
            .withName("test-taskrun")
            .endMetadata()
            .withNewStatus()
            .withConditions(failed)
            .withResults(List.of())
            .endStatus()
            .build();

    CountDownLatch latch = new CountDownLatch(1);
    TaskWatcher watcher = new TaskWatcher(latch, "test-taskrun");

    assertDoesNotThrow(() -> watcher.eventReceived(Watcher.Action.MODIFIED, taskRun));

    assertEquals(0, latch.getCount());
    assertNotNull(watcher.getCondition());
    assertEquals("False", watcher.getCondition().getStatus());
  }

  @Test
  public void testATerminalTaskRunOfAnotherNameIsIgnored() {
    // Every attempt of a task carries the same labels, so the watch also delivers an earlier
    // attempt's cancelled TaskRun; it must not end the attempt being watched.
    Condition cancelled = new Condition();
    cancelled.setType("Succeeded");
    cancelled.setStatus("False");
    cancelled.setReason("TaskRunCancelled");
    TaskRun earlierAttempt =
        new TaskRunBuilder()
            .withNewMetadata()
            .withName("test-taskrun-attempt-1")
            .endMetadata()
            .withNewStatus()
            .withConditions(cancelled)
            .withResults(List.of())
            .endStatus()
            .build();

    CountDownLatch latch = new CountDownLatch(1);
    TaskWatcher watcher = new TaskWatcher(latch, "test-taskrun-attempt-2");

    watcher.eventReceived(Watcher.Action.ADDED, earlierAttempt);
    watcher.evaluate(earlierAttempt);

    assertEquals(1, latch.getCount());
    assertNull(watcher.getCondition());
  }

  @Test
  public void testOnCloseSetsWatchLostAndDoesNotExitTheProcess() {
    CountDownLatch latch = new CountDownLatch(1);
    TaskWatcher watcher = new TaskWatcher(latch, "test-taskrun");

    assertFalse(watcher.isWatchLost());
    // If this still called System.exit(1), as it once did, this test process would terminate
    // instead of reaching the assertions below.
    assertDoesNotThrow(() -> watcher.onClose(new WatcherException("connection reset")));

    assertTrue(watcher.isWatchLost());
    watcher.resetWatchLost();
    assertFalse(watcher.isWatchLost());
  }

  @Test
  public void testMarkDeletedSetsAJobDeletedConditionAndCountsDownTheLatch() {
    CountDownLatch latch = new CountDownLatch(1);
    TaskWatcher watcher = new TaskWatcher(latch, "test-taskrun");

    watcher.markDeleted();

    assertEquals(0, latch.getCount());
    assertNotNull(watcher.getCondition());
    assertEquals("JobDeleted", watcher.getCondition().getReason());
  }
}
