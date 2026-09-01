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
import io.boomerang.common.model.RunRetry;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lease-based crash recovery: a dispatcher's first heartbeat for a TaskRun creates {@code
 * claim.leaseExpiresAt} - {@link TaskRunService#tryClaim} never sets one - so a dispatcher that
 * never heartbeats opts itself out of this reap entirely, and only {@link
 * WorkflowWatcher#reapClaimsFromGoneDispatchers} (deregistration/staleness) can recover it.
 */
class LeaseReapTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowWatcher watcher;

  private TaskRunEntity claimedTaskRun(String name, String claimedBy, Date leaseExpiresAt) {
    WorkflowRunEntity wfRun = savedWorkflowRun(name + "-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            name, TaskType.template, RunStatus.running, RunPhase.running, wfRun.getWorkflowRef(), wfRun.getId());
    RunClaim claim = new RunClaim();
    claim.setBy(claimedBy);
    claim.setAt(new Date());
    claim.setSeq(1L);
    claim.setLeaseExpiresAt(leaseExpiresAt);
    taskRun.setClaim(claim);
    return taskRunRepository.save(taskRun);
  }

  @Test
  void expiredLeaseOnAClaimedRunningTaskIsRequeuedAsAttemptOne() {
    TaskRunEntity taskRun =
        claimedTaskRun(
            "lease-expired-requeue",
            "dispatcher-a",
            new Date(System.currentTimeMillis() - 1000));

    watcher.reapExpiredLeases();

    TaskRunEntity reaped = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    // Same shape tryRequeue produces for a claimed attempt: parked awaiting termination of
    // whatever the lapsed claimant provisioned, not straight back on the execution queue.
    assertEquals(RunStatus.waiting, reaped.getStatus());
    assertEquals(RunPhase.pending, reaped.getPhase());
    assertEquals("dispatcher-a", reaped.getClaim().getBy(), "the claim survives as the pod marker");
    assertEquals(2L, reaped.getClaim().getSeq(), "the requeue supersedes the lapsed claimant");
    assertNotNull(reaped.getRetry());
    assertEquals(1, reaped.getRetry().getCount());
  }

  @Test
  void aLeaseInTheFutureIsUntouched() {
    TaskRunEntity taskRun =
        claimedTaskRun(
            "lease-future", "dispatcher-b", new Date(System.currentTimeMillis() + 600000));

    watcher.reapExpiredLeases();

    TaskRunEntity untouched = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.running, untouched.getStatus());
    assertEquals(RunPhase.running, untouched.getPhase());
    assertEquals("dispatcher-b", untouched.getClaim().getBy());
  }

  @Test
  void aClaimedRunWithNoLeaseIsUntouched() {
    // A dispatcher that never heartbeated: claim.by is set, claim.leaseExpiresAt never was.
    TaskRunEntity taskRun = claimedTaskRun("lease-never-set", "dispatcher-c", null);

    watcher.reapExpiredLeases();

    TaskRunEntity untouched = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.running, untouched.getStatus());
    assertEquals(RunPhase.running, untouched.getPhase());
    assertEquals("dispatcher-c", untouched.getClaim().getBy());
    assertNull(untouched.getRetry(), "an unreaped run gets no retry block");
  }

  @Test
  void anExpiredLeaseWithRetriesExhaustedIsAbandoned() {
    TaskRunEntity taskRun =
        claimedTaskRun(
            "lease-expired-exhausted",
            "dispatcher-d",
            new Date(System.currentTimeMillis() - 1000));
    RunRetry retry = new RunRetry();
    retry.setCount(3); // MAX_RETRIES already spent
    taskRun.setRetry(retry);
    taskRunRepository.save(taskRun);

    watcher.reapExpiredLeases();

    TaskRunEntity abandoned = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.timedout, abandoned.getStatus());
    assertEquals("LeaseExpired", abandoned.getStatusReason());
  }

  @Test
  void requeueableTypesOnlyRequeueOnLeaseExpiry() {
    // A gate/system TaskRun (not in REQUEUEABLE_TYPES) with an expired lease goes straight to
    // abandon regardless of retry budget.
    WorkflowRunEntity wfRun =
        savedWorkflowRun("lease-gate-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "lease-gate",
            TaskType.decision,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    RunClaim claim = new RunClaim();
    claim.setBy("dispatcher-e");
    claim.setAt(new Date());
    claim.setSeq(1L);
    claim.setLeaseExpiresAt(new Date(System.currentTimeMillis() - 1000));
    taskRun.setClaim(claim);
    taskRunRepository.save(taskRun);

    watcher.reapExpiredLeases();

    TaskRunEntity abandoned = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.timedout, abandoned.getStatus());
    assertEquals("LeaseExpired", abandoned.getStatusReason());
  }
}
