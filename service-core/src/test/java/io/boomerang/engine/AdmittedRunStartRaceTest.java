package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The admitted-run sweep's start of a run whose start was lost, when another caller starts the
 * run at the same moment. The other start lands where a second instance's would: between the
 * sweep's read of the run and the graph it builds to start it.
 */
class AdmittedRunStartRaceTest extends AbstractSpiedEngineIntegrationTest {

  @Autowired private WorkflowExecutionService workflowExecutionService;
  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;

  // Admitted with nothing to provision and no TaskRuns of its own, so only this run builds an
  // empty graph.
  private String admittedRun() {
    return savedWorkflowRun("admitted-run-start-race", RunStatus.ready, RunPhase.pending).getId();
  }

  // Another caller starts the run, and this start then fails: the end it would take is only from
  // pending, so the run that started is left as it is.
  @Test
  void aRunStartedMeanwhileIsNotEndedWhenTheSweepsStartFails() {
    String id = admittedRun();
    doAnswer(
            invocation -> {
              workflowRunStateHelper.tryStart(id, new Date(), null);
              throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_DEPENDENCY, "missing");
            })
        .when(dagUtility)
        .createGraph(argThat((List<TaskRunEntity> tasks) -> tasks.isEmpty()));

    workflowExecutionService.startAdmitted(id);

    WorkflowRunEntity run = workflowRunRepository.findById(id).orElseThrow();
    assertEquals(RunStatus.running, run.getStatus());
    assertEquals(RunPhase.running, run.getPhase());
    assertNull(run.getStatusReason());
    workflowRunStateHelper.tryComplete(
        id, List.of(RunPhase.running), RunStatus.cancelled, null, null, 0);
  }

  // The start lost the race outright: the run is the other caller's, and nothing is ended.
  @Test
  void aLostStartRaceEndsNothing() {
    String id = admittedRun();
    doAnswer(
            invocation -> {
              throw new BoomerangException(
                  BoomerangError.WORKFLOWRUN_INVALID_PHASE, RunPhase.running, RunPhase.pending);
            })
        .when(dagUtility)
        .createGraph(argThat((List<TaskRunEntity> tasks) -> tasks.isEmpty()));

    workflowExecutionService.startAdmitted(id);

    WorkflowRunEntity run = workflowRunRepository.findById(id).orElseThrow();
    assertEquals(RunStatus.ready, run.getStatus());
    assertEquals(RunPhase.pending, run.getPhase());
    workflowRunStateHelper.tryComplete(
        id, List.of(RunPhase.pending), RunStatus.cancelled, null, null, 0);
  }

  // A database that failed for a moment ends nothing: the run is tried again on the next sweep.
  @Test
  void aDatabaseErrorDuringTheStartEndsNothing() {
    String id = admittedRun();
    doAnswer(
            invocation -> {
              throw new org.springframework.dao.DataAccessResourceFailureException("blip");
            })
        .when(dagUtility)
        .createGraph(argThat((List<TaskRunEntity> tasks) -> tasks.isEmpty()));

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> workflowExecutionService.startAdmitted(id));

    WorkflowRunEntity run = workflowRunRepository.findById(id).orElseThrow();
    assertEquals(RunStatus.ready, run.getStatus());
    assertEquals(RunPhase.pending, run.getPhase());
    workflowRunStateHelper.tryComplete(
        id, List.of(RunPhase.pending), RunStatus.cancelled, null, null, 0);
  }
}
