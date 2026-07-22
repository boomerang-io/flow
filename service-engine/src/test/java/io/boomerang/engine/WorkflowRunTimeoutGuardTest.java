package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Terminal-status protection on the workflow timeout path. RED-LINE: flips in E4 (B10 CAS).
 * Passes today by demonstrating the current defect (idempotency-audit.md #25):
 * WorkflowRunService.timeout writes status=timedout with no precondition, stomping an
 * already-succeeded run. When E4's phase==running CAS lands, invert: succeeded is preserved.
 *
 * <p>Lives in io.boomerang.engine because timeout is package-visible, yet reachable in production
 * from public paths (stale timeout checks in TaskExecutionService.start/end - audit #20/#9). The
 * TaskRun-level stomp (audit #9) is not deterministically reachable via public APIs; it is
 * covered by E4's completion CAS (B1).
 */
class WorkflowRunTimeoutGuardTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunService workflowRunService;

  // RED-LINE: flips in E4 (B10 CAS precondition).
  @Test
  void lateTimeoutOverwritesSucceededTerminalStatus() {
    String runId =
        savedWorkflowRun("timeout-guard-wf", RunStatus.succeeded, RunPhase.completed).getId();

    // The timeout path firing late, e.g. a stale task-timeout future racing the completion.
    workflowRunService.timeout(runId, false);

    WorkflowRunEntity after = workflowRunRepository.findById(runId).orElseThrow();
    assertEquals(
        RunStatus.timedout,
        after.getStatus(),
        "DEFECT (audit #25): timeout has no precondition and overwrites the succeeded terminal"
            + " status. When E4's CAS lands, flip this to expect RunStatus.succeeded.");
    // Phase stays completed - only the externally-visible status is corrupted, which is exactly
    // why this defect matters (status is the only external-facing field).
    assertEquals(RunPhase.completed, after.getPhase());
  }
}
