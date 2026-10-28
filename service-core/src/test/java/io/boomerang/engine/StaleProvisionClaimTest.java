package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowWorkspace;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * A run claimed for workspace provisioning that never starts - the dispatcher failed to provision
 * or died holding the claim - is released for another attempt, and failed once its attempts are
 * spent, rather than staying claimed forever.
 */
class StaleProvisionClaimTest extends AbstractEngineIntegrationTest {

  private static final String DISPATCHER = "dispatcher-stale-provision";

  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;
  @Autowired private WorkflowWatcher workflowWatcher;
  @Autowired private WorkflowExecutionService workflowExecutionService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void aStaleProvisioningClaimIsReleasedAndClaimableAgain() {
    String runId = claimableRun("stale-provision-release");
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));
    backdateClaim(runId);

    workflowWatcher.recoverStaleProvisionClaims();

    WorkflowRunEntity released = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(RunStatus.ready, released.getStatus());
    assertEquals(RunPhase.pending, released.getPhase());
    assertNull(released.getClaim().getBy());
    assertNull(released.getClaim().getAt());
    assertEquals(1L, released.getClaim().getSeq());
    WorkflowRunEntity reclaimed = workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER);
    assertNotNull(reclaimed, "a released run must be claimable by the next poll");
    assertEquals(RunPhase.queued, reclaimed.getPhase());
  }

  @Test
  void aProvisioningClaimWithinTheGracePeriodIsLeftAlone() {
    String runId = claimableRun("stale-provision-fresh");
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));

    workflowWatcher.recoverStaleProvisionClaims();

    WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(RunPhase.queued, run.getPhase());
    assertEquals(DISPATCHER, run.getClaim().getBy());
  }

  @Test
  void aRunWhoseLastProvisioningAttemptGoesStaleFails() {
    String runId = claimableRun("stale-provision-fail");
    for (int attempt = 1; attempt < 3; attempt++) {
      assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));
      backdateClaim(runId);
      workflowWatcher.recoverStaleProvisionClaims();
      assertEquals(RunPhase.pending, workflowRunRepository.findById(runId).orElseThrow().getPhase());
    }
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));
    backdateClaim(runId);

    workflowWatcher.recoverStaleProvisionClaims();

    WorkflowRunEntity failed = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(RunStatus.failed, failed.getStatus());
    assertEquals(RunPhase.completed, failed.getPhase());
    assertEquals(
        "Workspace provisioning did not complete after 3 attempts.", failed.getStatusMessage());
    assertNull(
        workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER),
        "a failed run is never claimable again");
  }

  @Test
  void aReportedFailureHoldsTheClaimWithItsReasonUntilTheRetryDelayEnds() {
    String runId = claimableRun("reported-provision-hold");
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));

    assertTrue(workflowExecutionService.reportProvisionFailure(runId, DISPATCHER, "Connection refused"));

    WorkflowRunEntity held = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(RunPhase.queued, held.getPhase());
    assertEquals(DISPATCHER, held.getClaim().getBy());
    assertEquals(
        "Workspace provisioning failed: Connection refused. Retrying in 1 minute (attempt 1 of 3).",
        held.getStatusMessage());
    assertTrue(held.getClaim().getLeaseExpiresAt().after(new Date()), "the next attempt waits out the delay");
    workflowWatcher.recoverStaleProvisionClaims();
    assertEquals(RunPhase.queued, workflowRunRepository.findById(runId).orElseThrow().getPhase());

    endLease(runId);
    workflowWatcher.recoverStaleProvisionClaims();

    WorkflowRunEntity released = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(RunPhase.pending, released.getPhase());
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER), "the next poll retries");
  }

  @Test
  void theLastReportedFailureFailsTheRunWithItsReason() {
    String runId = claimableRun("reported-provision-fail");
    for (int attempt = 1; attempt < 3; attempt++) {
      assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));
      assertTrue(workflowExecutionService.reportProvisionFailure(runId, DISPATCHER, "Connection refused"));
      endLease(runId);
      workflowWatcher.recoverStaleProvisionClaims();
    }
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));

    assertTrue(workflowExecutionService.reportProvisionFailure(runId, DISPATCHER, "Connection refused"));

    WorkflowRunEntity failed = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(RunStatus.failed, failed.getStatus());
    assertEquals(RunPhase.completed, failed.getPhase());
    assertEquals("Workspace provisioning failed after 3 attempts: Connection refused", failed.getStatusMessage());
  }

  @Test
  void aReportForARunTheDispatcherNoLongerHoldsIsIgnored() {
    String runId = claimableRun("reported-provision-other");
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));

    assertFalse(workflowExecutionService.reportProvisionFailure(runId, "another-dispatcher", "Connection refused"));

    WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
    assertNull(run.getStatusMessage());
    assertNull(run.getClaim().getLeaseExpiresAt());
  }

  @Test
  void aStartClearsTheRetryingMessage() {
    String runId = claimableRun("reported-provision-start");
    assertNotNull(workflowRunStateHelper.tryClaimForProvision(runId, DISPATCHER));
    assertTrue(workflowExecutionService.reportProvisionFailure(runId, DISPATCHER, "Connection refused"));

    assertNotNull(workflowRunStateHelper.tryStart(runId, new Date(), null));

    assertNull(workflowRunRepository.findById(runId).orElseThrow().getStatusMessage());
  }

  private void endLease(String runId) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(runId)),
        new Update().set("claim.leaseExpiresAt", new Date(System.currentTimeMillis() - 1000)),
        WorkflowRunEntity.class);
  }

  private String claimableRun(String workflowRef) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setWorkflowRef(workflowRef);
    // A revision that does not resolve: a failed run winds down what is stored.
    run.setWorkflowRevisionRef("missing-revision");
    run.setStatus(RunStatus.ready);
    run.setPhase(RunPhase.pending);
    run.setCreationDate(new Date());
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("1Gi");
    run.setWorkspace(workspace);
    return workflowRunRepository.save(run).getId();
  }

  private void backdateClaim(String runId) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(runId)),
        new Update().set("claim.at", new Date(System.currentTimeMillis() - 3_600_000)),
        WorkflowRunEntity.class);
  }
}
