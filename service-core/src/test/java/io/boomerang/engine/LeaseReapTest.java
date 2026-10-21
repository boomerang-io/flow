package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunRetry;
import io.boomerang.dispatcher.DispatcherService;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Lease-based crash recovery: {@link TaskRunService#tryClaim} sets {@code claim.leaseExpiresAt}
 * one lease length ahead and each heartbeat renews it, so a claim its dispatcher stops reporting
 * is requeued or abandoned once the lease lapses, even while the dispatcher itself stays live.
 */
class LeaseReapTest extends AbstractEngineIntegrationTest {

  private static final long LEASE_MILLIS = 90000;

  @Autowired private WorkflowWatcher watcher;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private DispatcherService dispatcherService;

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
  void completingAClaimedTaskClearsItsLeaseSoTheSweepIndexHoldsOnlyLiveClaims() {
    TaskRunEntity taskRun =
        claimedTaskRun(
            "lease-cleared-on-complete",
            "dispatcher-c",
            new Date(System.currentTimeMillis() + 60_000));

    taskRunService.tryComplete(
        taskRun.getId(),
        java.util.Optional.of(RunStatus.succeeded),
        java.util.Optional.empty(),
        0L,
        java.util.Optional.of("dispatcher-c"),
        java.util.Optional.empty());

    TaskRunEntity completed = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunPhase.completed, completed.getPhase());
    assertEquals("dispatcher-c", completed.getClaim().getBy(), "the claimant stays for termination");
    assertEquals(null, completed.getClaim().getLeaseExpiresAt());
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

  // The sweep pages only the claims of dispatchers that are gone, so a page's worth of live claims,
  // older than it, cannot keep a gone dispatcher's claim from being reaped.
  @Test
  void aGoneDispatchersClaimBehindAPageOfLiveClaimsIsReaped() {
    String live =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "lease-live-page", "lease-live-page.local", List.of("template")));
    WorkflowRunEntity wfRun =
        savedWorkflowRun("lease-live-page-wf", RunStatus.running, RunPhase.running);
    Date older = new Date(System.currentTimeMillis() - 10 * 60 * 1000);
    for (int i = 0; i < 55; i++) {
      String id = claimedTaskRun(wfRun, "live-" + i, live);
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(id)),
          new Update().set("claim.at", older),
          TaskRunEntity.class);
    }
    String gone = claimedTaskRun(wfRun, "gone", "lease-gone-dispatcher-never-registered");
    dispatcherService.heartbeat(live, List.of());

    watcher.reapClaimsFromGoneDispatchers();

    TaskRunEntity reaped = taskRunRepository.findById(gone).orElseThrow();
    assertEquals(RunStatus.waiting, reaped.getStatus(), "the gone dispatcher's claim is requeued");
    assertEquals(RunPhase.pending, reaped.getPhase());
    assertEquals(
        live,
        taskRunRepository
            .findFirstByNameAndWorkflowRunRef("live-0", wfRun.getId())
            .orElseThrow()
            .getClaim()
            .getBy(),
        "a live dispatcher's claim is untouched");
  }

  private String claimedTaskRun(WorkflowRunEntity wfRun, String name, String dispatcherId) {
    TaskRunEntity taskRun =
        savedTaskRun(
            name,
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    taskRunService.tryClaim(taskRun.getId(), dispatcherId);
    return taskRun.getId();
  }

  @Test
  void aClaimTheRestartedDispatcherNoLongerReportsIsReapedOnceItsClaimTimeLeaseLapses() {
    DispatcherRegistrationRequest registration =
        new DispatcherRegistrationRequest("lease-restart", "lease-restart.local", List.of("template"));
    String dispatcherId = dispatcherService.register(registration);
    WorkflowRunEntity wfRun = savedWorkflowRun("lease-restart-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "lease-restart",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    taskRunService.tryClaim(taskRun.getId(), dispatcherId);

    RunClaim claim = taskRunRepository.findById(taskRun.getId()).orElseThrow().getClaim();
    assertNotNull(claim.getLeaseExpiresAt(), "the claim sets the first lease");
    assertEquals(claim.getAt().getTime() + LEASE_MILLIS, claim.getLeaseExpiresAt().getTime());

    // The process crashes before its first heartbeat and starts again under the same name and
    // host: the same id comes back, stays live, and heartbeats without the claim it lost.
    assertEquals(dispatcherId, dispatcherService.register(registration));
    dispatcherService.heartbeat(dispatcherId, List.of());
    watcher.reapClaimsFromGoneDispatchers();
    assertEquals(
        RunPhase.queued,
        taskRunRepository.findById(taskRun.getId()).orElseThrow().getPhase(),
        "a live dispatcher's claim is not reaped as gone");

    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRun.getId())),
        new Update().set("claim.leaseExpiresAt", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);
    watcher.reapExpiredLeases();

    TaskRunEntity reaped = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.waiting, reaped.getStatus());
    assertEquals(RunPhase.pending, reaped.getPhase());
    assertEquals(1, reaped.getRetry().getCount());
  }

  @Test
  void aClaimedRunWithNoLeaseIsUntouched() {
    // A claim with no lease is left to the deadline and gone-dispatcher sweeps.
    TaskRunEntity taskRun = claimedTaskRun("lease-never-set", "dispatcher-c", null);

    watcher.reapExpiredLeases();

    TaskRunEntity untouched = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.running, untouched.getStatus());
    assertEquals(RunPhase.running, untouched.getPhase());
    assertEquals("dispatcher-c", untouched.getClaim().getBy());
    assertNull(untouched.getRetry(), "an unreaped run gets no retry block");
  }

  @Test
  void anAbandonKeepsItsLeaseSoALostEndIsReapedAgain() {
    TaskRunEntity taskRun =
        claimedTaskRun(
            "lease-abandon-lost-end", "dispatcher-g", new Date(System.currentTimeMillis() - 1000));

    // The abandon lands but its end never does.
    taskRunService.tryAbandon(taskRun.getId(), 1L, "lease lapsed", "LeaseExpired", false);

    TaskRunEntity abandoned = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.timedout, abandoned.getStatus());
    assertNotNull(abandoned.getClaim().getLeaseExpiresAt(), "the lease stays until the end lands");
    assertEquals(
        1,
        taskRunService.findLeaseExpired(100).stream()
            .filter(t -> t.getId().equals(taskRun.getId()))
            .count(),
        "the next lease sweep finds it again");
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
