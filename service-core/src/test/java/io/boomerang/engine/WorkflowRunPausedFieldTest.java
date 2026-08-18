package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * pauseRequestedAt is the entity's source of truth and is never itself put on the wire - the
 * public model only ever exposes the derived boolean. Pause is orthogonal to status/phase: a
 * paused run must keep whatever status and phase it already had.
 */
class WorkflowRunPausedFieldTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunService workflowRunService;

  @Test
  void pausingARunFlipsTheWireBooleanWithoutChangingStatusOrPhase() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("paused-field-wf", RunStatus.running, RunPhase.running);

    WorkflowRun beforePause = workflowRunService.get(wfRun.getId(), false);
    assertFalse(beforePause.isPaused(), "a run with no pauseRequestedAt must report unpaused");

    boolean won = workflowRunService.tryPause(wfRun.getId());
    assertTrue(won, "the Compare-And-Set must win on a running, not-yet-paused run");

    WorkflowRunEntity persisted = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
    assertTrue(persisted.isPaused(), "the entity's derived accessor must reflect pauseRequestedAt");

    WorkflowRun afterPause = workflowRunService.get(wfRun.getId(), false);
    assertTrue(afterPause.isPaused(), "the public model must expose the derived boolean");
    assertEquals(RunStatus.running, afterPause.getStatus(), "pause must not change status");
    assertEquals(RunPhase.running, afterPause.getPhase(), "pause must not change phase");
  }
}
