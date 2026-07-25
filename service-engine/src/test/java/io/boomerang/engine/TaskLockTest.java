package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The user-facing cross-workflow mutex on {@code task_locks}: exactly one holder at a time, an
 * expired lease is taken over, only the holder releases, and workspace-scoped keys never collide.
 */
class TaskLockTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;

  private static Date future() {
    return new Date(System.currentTimeMillis() + 60000);
  }

  @Test
  void oneTaskHoldsAContendedLock() {
    assertNotNull(taskExecutionService.tryAcquire("ws:deploy", "task-a", "wf1", future()));
    assertNull(
        taskExecutionService.tryAcquire("ws:deploy", "task-b", "wf2", future()),
        "a live lock held by another task is never stolen");

    // A non-holder cannot release it.
    taskExecutionService.release("ws:deploy", "task-b");
    assertNull(taskExecutionService.tryAcquire("ws:deploy", "task-b", "wf2", future()));

    // The holder releases; the key is now free.
    taskExecutionService.release("ws:deploy", "task-a");
    assertNotNull(taskExecutionService.tryAcquire("ws:deploy", "task-b", "wf2", future()));
  }

  @Test
  void expiredLeaseIsTakenOver() {
    Date past = new Date(System.currentTimeMillis() - 1000);
    assertNotNull(taskExecutionService.tryAcquire("ws:expired", "task-a", "wf1", past));
    assertNotNull(
        taskExecutionService.tryAcquire("ws:expired", "task-b", "wf2", future()),
        "an expired lease is reclaimed by the next acquirer");
  }

  @Test
  void sameKeyInDifferentWorkspacesDoesNotCollide() {
    assertNotNull(taskExecutionService.tryAcquire("ws1:deploy", "task-a", "wf1", future()));
    assertNotNull(
        taskExecutionService.tryAcquire("ws2:deploy", "task-b", "wf2", future()),
        "the workspace scope keeps identical user keys independent");
  }
}
