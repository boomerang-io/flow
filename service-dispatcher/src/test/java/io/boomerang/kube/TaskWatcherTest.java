package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    TaskWatcher watcher = new TaskWatcher(latch);

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
    TaskWatcher watcher = new TaskWatcher(latch);

    assertDoesNotThrow(() -> watcher.eventReceived(Watcher.Action.MODIFIED, taskRun));

    assertEquals(0, latch.getCount());
    assertNotNull(watcher.getCondition());
    assertEquals("False", watcher.getCondition().getStatus());
  }

  @Test
  public void testOnCloseSetsWatchLostAndDoesNotExitTheProcess() {
    CountDownLatch latch = new CountDownLatch(1);
    TaskWatcher watcher = new TaskWatcher(latch);

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
    TaskWatcher watcher = new TaskWatcher(latch);

    watcher.markDeleted();

    assertEquals(0, latch.getCount());
    assertNotNull(watcher.getCondition());
    assertEquals("JobDeleted", watcher.getCondition().getReason());
  }
}
