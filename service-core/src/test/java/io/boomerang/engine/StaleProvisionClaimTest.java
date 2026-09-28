package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowWorkspace;
import java.util.Date;
import java.util.List;
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

  private String claimableRun(String workflowRef) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setWorkflowRef(workflowRef);
    run.setStatus(RunStatus.ready);
    run.setPhase(RunPhase.pending);
    run.setCreationDate(new Date());
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setName("run-store");
    workspace.setType("workflowrun");
    run.setWorkspaces(List.of(workspace));
    return workflowRunRepository.save(run).getId();
  }

  private void backdateClaim(String runId) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(runId)),
        new Update().set("claim.at", new Date(System.currentTimeMillis() - 3_600_000)),
        WorkflowRunEntity.class);
  }
}
