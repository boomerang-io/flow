package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunClaim;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * The reaps are fenced on the claim seq they observed: a reap that read the claim before it moved on
 * - re-claimed, or requeued by another reaper - changes nothing, so the newer claim wins.
 */
class ClaimSeqFencedReapTest extends AbstractEngineIntegrationTest {

  private static final long CURRENT_SEQ = 2L;
  private static final long STALE_SEQ = 1L;

  // A running task claimed at seq 2, already past its deadline.
  private String claimedTask(String tag) {
    WorkflowRunEntity wfRun = savedWorkflowRun(tag + "-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            tag + "-task",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    RunClaim claim = new RunClaim();
    claim.setBy("fenced-dispatcher");
    claim.setAt(new Date());
    claim.setSeq(CURRENT_SEQ);
    task.setClaim(claim);
    task.setTimeoutAt(new Date(System.currentTimeMillis() - 1000));
    return taskRunRepository.save(task).getId();
  }

  private void assertUntouched(String id) {
    TaskRunEntity after = taskRunRepository.findById(id).orElseThrow();
    assertEquals(RunStatus.running, after.getStatus());
    assertEquals(RunPhase.running, after.getPhase());
    assertEquals(CURRENT_SEQ, after.getClaim().getSeq());
  }

  @Test
  void aRequeueThatObservedAnOlderClaimChangesNothing() {
    String id = claimedTask("fence-requeue");

    assertNull(taskRunService.tryRequeue(id, STALE_SEQ, new Date(), 1));
    assertUntouched(id);
    assertNotNull(taskRunService.tryRequeue(id, CURRENT_SEQ, new Date(), 1));
  }

  @Test
  void aTimeoutThatObservedAnOlderClaimChangesNothing() {
    String id = claimedTask("fence-timeout");

    assertNull(taskRunService.tryTimeout(id, STALE_SEQ, "timed out"));
    assertUntouched(id);
    assertNotNull(taskRunService.tryTimeout(id, CURRENT_SEQ, "timed out"));
  }

  @Test
  void anAbandonThatObservedAnOlderClaimChangesNothing() {
    String id = claimedTask("fence-abandon");

    assertNull(taskRunService.tryAbandon(id, STALE_SEQ, "gone", "DispatcherGone"));
    assertUntouched(id);
    assertNotNull(taskRunService.tryAbandon(id, CURRENT_SEQ, "gone", "DispatcherGone"));
  }
}
