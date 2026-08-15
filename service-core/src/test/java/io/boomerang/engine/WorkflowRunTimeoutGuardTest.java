package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
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
}
