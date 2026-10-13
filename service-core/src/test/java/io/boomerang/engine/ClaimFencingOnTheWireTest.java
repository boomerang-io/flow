package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.WorkflowRunService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * The two gaps the E4 audit pinned as characterisation tests, now closed: a superseded dispatcher
 * identifies itself on the wire ({@code dispatcherRef}) and is fenced, and a cancel whose revision
 * no longer resolves still winds down the run's in-flight TaskRuns.
 */
class ClaimFencingOnTheWireTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowRunService workflowRunService;

  private String registerDispatcher(String name) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name, name + ".local", List.of("template")));
  }

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  // Drives the exact sequence a superseded dispatcher produces: A claims (seq 1), the watcher
  // requeues the claim, B claims (seq 3 after the termination hand-off), then A - still alive -
  // reports its result. Returns {taskRunId, dispatcherA, dispatcherB}.
  private String[] supersededClaimantScenario(String tag) {
    WorkflowRunEntity wfRun = savedWorkflowRun(tag + "-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                tag + "-task",
                TaskType.template,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    String dispatcherA = registerDispatcher(tag + "-a");
    String dispatcherB = registerDispatcher(tag + "-b");

    assertTrue(containsId(dispatcherService.getTaskQueue(dispatcherA), taskRunId));
    assertEquals(1L, taskRunRepository.findById(taskRunId).orElseThrow().getClaim().getSeq());

    assertNotNull(
        taskRunService.tryRequeue(
            taskRunId, 1L, new Date(System.currentTimeMillis() - 1000), 1));

    assertTrue(containsId(dispatcherService.getTaskQueue(dispatcherB), taskRunId));
    assertTrue(containsId(dispatcherService.getTaskQueue(dispatcherB), taskRunId));
    TaskRunEntity reclaimed = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(dispatcherB, reclaimed.getClaim().getBy());
    assertEquals(3L, reclaimed.getClaim().getSeq());
    return new String[] {taskRunId, dispatcherA, dispatcherB};
  }

  @Test
  void supersededClaimantResultIsRejectedOnTheDispatcherWire() {
    String[] scenario = supersededClaimantScenario("superseded");
    String taskRunId = scenario[0];

    // Dispatcher A - superseded, but still alive - reports failure through the exact call
    // DispatcherControllerV1.endTaskRun makes, naming itself as every dispatcher now does.
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.failed);
    endRequest.setStatusMessage("stale pod result");
    endRequest.setDispatcherRef(scenario[1]);
    BoomerangException rejected =
        assertThrows(
            BoomerangException.class, () -> taskRunService.end(taskRunId, Optional.of(endRequest)));
    assertEquals(BoomerangError.TASKRUN_CLAIM_SUPERSEDED.getCode(), rejected.getCode());

    TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
    assertNotEquals(
        RunPhase.completed,
        after.getPhase(),
        "a superseded claimant's result must not complete the current claimant's TaskRun");
    assertEquals(scenario[2], after.getClaim().getBy(), "the current claim is untouched");
    assertEquals(
        null, after.getStatusMessage(), "the rejected request's merge must not be written");
  }

  @Test
  void currentClaimantResultIsAcceptedOnTheDispatcherWire() {
    String[] scenario = supersededClaimantScenario("current");
    String taskRunId = scenario[0];

    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    endRequest.setDispatcherRef(scenario[2]);
    taskRunService.end(taskRunId, Optional.of(endRequest));

    awaitEngine("the current claimant's end to be applied")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.succeeded, after.getStatus());
            });
  }

  @Test
  void legacyRequestWithoutDispatcherRefIsStillAccepted() {
    String taskRunId = supersededClaimantScenario("legacy")[0];

    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    taskRunService.end(taskRunId, Optional.of(endRequest));

    awaitEngine("the unfenced legacy end to be applied")
        .untilAsserted(
            () ->
                assertEquals(
                    RunPhase.completed,
                    taskRunRepository.findById(taskRunId).orElseThrow().getPhase()));
  }

  /** Tombstone delete must never orphan in-flight work, revision present or not. */
  @Test
  void cancelWithMissingRevisionStillCancelsInFlightTasks() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("orphan-cancel-wf", RunStatus.running, RunPhase.running);
    wfRun.setWorkflowRevisionRef("revision-that-no-longer-exists");
    workflowRunRepository.save(wfRun);

    TaskRunEntity taskRun =
        savedTaskRun(
            "should-be-cancelled",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    workflowRunService.cancel(wfRun.getId());

    awaitEngine("the in-flight TaskRun to be wound down with its WorkflowRun")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(taskRun.getId()).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.cancelled, after.getStatus());
            });
    WorkflowRunEntity cancelledRun = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
    assertEquals(RunStatus.cancelled, cancelledRun.getStatus());
  }
}
