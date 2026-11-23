package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Terminal-status protection on the workflow timeout path. The timeout transition is a
 * Compare-And-Set with a running-phase precondition, so a timeout firing late (for example a
 * stale timer racing the completion) can never overwrite an already-terminal status.
 *
 * <p>Lives in io.boomerang.engine because timeout is package-visible, yet reachable in production
 * from public paths (the task-timeout follow-on and the watcher's timeout reap).
 */
class WorkflowRunTimeoutGuardTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowWatcher workflowWatcher;

  @Test
  void lateTimeoutCannotOverwriteSucceededTerminalStatus() {
    String runId =
        savedWorkflowRun("timeout-guard-wf", RunStatus.succeeded, RunPhase.completed).getId();

    // The timeout path firing late, e.g. a stale timer racing the completion.
    workflowRunService.timeout(runId, false);

    WorkflowRunEntity after = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(
        RunStatus.succeeded,
        after.getStatus(),
        "a late timeout must never overwrite a terminal status");
    assertEquals(RunPhase.completed, after.getPhase());
  }

  // Time spent waiting for a slot is not the run's: a run parked with a workspace to provision
  // carries no deadline, and its admission stamps one from then, for the wait for a dispatcher.
  @Test
  void aParkedRunWithAWorkspaceHasNoDeadlineAndTakesOneAtAdmission() {
    seedGlobalTask("timeout-guard-queue-task");
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(1);
    WorkspaceRequest workspaceRequest = new WorkspaceRequest();
    workspaceRequest.setName("timeout-guard-queue");
    workspaceRequest.setDisplayName("timeout-guard-queue");
    workspaceRequest.setQuotas(quotas);
    String workspace = workspaceService.create(workspaceRequest).getName();
    Workflow workflow = runnableWorkflow("timeout-guard-queue-workflow", "timeout-guard-queue-task");
    WorkflowWorkspace volume = new WorkflowWorkspace();
    volume.setSize("1Gi");
    workflow.setWorkspace(volume);
    workflowService.create(workspace, workflow);
    setFeatureSetting("workspaceQuotas", true);
    try {
      WorkflowSubmitRequest request = new WorkflowSubmitRequest();
      request.setTrigger(TriggerEnum.manual);
      request.setTimeout(10L);
      WorkflowRun holder =
          workflowService.submit(workspace, "timeout-guard-queue-workflow", request, true);
      WorkflowRun parked =
          workflowService.submit(workspace, "timeout-guard-queue-workflow", request, true);

      assertNotNull(
          workflowRunRepository.findById(holder.getId()).orElseThrow().getTimeoutAt(),
          "an admitted run waiting for a dispatcher has a deadline");
      WorkflowRunEntity waiting = workflowRunRepository.findById(parked.getId()).orElseThrow();
      assertEquals(RunStatus.waiting, waiting.getStatus());
      assertNull(waiting.getTimeoutAt(), "a parked run has no deadline");

      Date beforeAdmission = new Date();
      workflowRunService.cancel(holder.getId());

      WorkflowRunEntity admitted = workflowRunRepository.findById(parked.getId()).orElseThrow();
      assertEquals(RunStatus.ready, admitted.getStatus(), "admitted when the slot freed");
      assertTrue(
          admitted.getTimeoutAt().getTime() >= beforeAdmission.getTime() + 10 * 60000L,
          "the deadline runs from admission, not from creation");
      workflowRunService.cancel(parked.getId());
    } finally {
      setFeatureSetting("workspaceQuotas", false);
    }
  }

  // A parked run stored with a deadline, as an earlier release stamped one at creation, is not
  // timed out while it waits.
  @Test
  void theTimeoutSweepLeavesAParkedRunThatCarriesADeadline() {
    WorkflowRunEntity parked =
        savedWorkflowRun("timeout-guard-parked-wf", RunStatus.waiting, RunPhase.pending);
    parked.setStartTime(null);
    parked.setTimeout(1L);
    parked.setTimeoutAt(new Date(System.currentTimeMillis() - 60000));
    workflowRunRepository.save(parked);

    workflowWatcher.reapWorkflowTimeouts();

    WorkflowRunEntity after = workflowRunRepository.findById(parked.getId()).orElseThrow();
    assertEquals(RunStatus.waiting, after.getStatus());
    assertEquals(RunPhase.pending, after.getPhase());
    // Ended here, so no later test's sweep finds it parked.
    after.setStatus(RunStatus.cancelled);
    after.setPhase(RunPhase.completed);
    workflowRunRepository.save(after);
  }
}
