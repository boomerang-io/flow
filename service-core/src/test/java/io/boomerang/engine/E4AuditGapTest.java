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
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.dispatcher.DispatcherService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * Characterization tests pinning two gaps found auditing E4 (execution-model rebuild). Each
 * "...Today" test asserts the CURRENT behaviour so the suite stays green and the gap is visible
 * in code; each paired {@code @Disabled} test states the behaviour E4 claims and fails today.
 * When a gap is fixed, delete the "...Today" test and enable its pair.
 */
class E4AuditGapTest extends AbstractEngineIntegrationTest {

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
  // requeues the claim, B claims (seq 2), then A - still alive - reports its result.
  private String supersededClaimantScenario(String tag) {
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

    // The real reap path: WorkflowWatcher.reapTaskTimeouts requeues a timed-out claim through
    // tryRequeue, which clears claim.by but never claim.seq. Backoff is set in the past so B can
    // claim on the next poll.
    assertNotNull(
        taskRunService.tryRequeue(
            taskRunId, 1L, new Date(System.currentTimeMillis() - 1000), 1));

    assertTrue(containsId(dispatcherService.getTaskQueue(dispatcherB), taskRunId));
    TaskRunEntity reclaimed = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(dispatcherB, reclaimed.getClaim().getBy());
    assertEquals(2L, reclaimed.getClaim().getSeq());
    return taskRunId;
  }

  /**
   * GAP (slice A/B - fencing). {@code TaskExecutionService.claimantIsValid} and the
   * {@code claimedBy}/{@code claimSeq} fencing predicates on {@code TaskRunService.tryComplete}
   * are unreachable from the shipped dispatcher wire: {@code TaskRunControllerV1} calls the
   * one-argument {@code TaskRunService.end}, and the {@code TaskRun} wire model deliberately
   * omits the {@code claim} block, so a dispatcher has no way to identify itself. Every real
   * request therefore takes the "no claimant identity - accepting as legacy protocol" branch and
   * a SUPERSEDED claimant's result completes the CURRENT claimant's TaskRun.
   */
  @Test
  void supersededClaimantResultIsAcceptedOnTheDispatcherWireToday() {
    String taskRunId = supersededClaimantScenario("superseded-today");

    // Dispatcher A - superseded, but still alive - reports failure through the exact call
    // TaskRunControllerV1.endTaskRun makes.
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.failed);
    taskRunService.end(taskRunId, Optional.of(endRequest));

    awaitEngine("the superseded claimant's end to be applied")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
              assertEquals(
                  RunPhase.completed,
                  after.getPhase(),
                  "current behaviour: the superseded claimant's result completes the TaskRun");
              assertEquals(RunStatus.failed, after.getStatus());
            });
  }

  /** The behaviour E4 claims ("fencing is genuinely validated on result writes"). Fails today. */
  @Test
  @Disabled("E4 gap: no dispatcher-wire path supplies claim identity, so fencing never engages")
  void supersededClaimantResultShouldBeRejectedOnTheDispatcherWire() {
    String taskRunId = supersededClaimantScenario("superseded-should");

    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.failed);
    taskRunService.end(taskRunId, Optional.of(endRequest));

    TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
    assertNotEquals(
        RunPhase.completed,
        after.getPhase(),
        "a superseded claimant's result must not complete the current claimant's TaskRun");
  }

  /**
   * GAP (slice F - tombstone delete / slice C - cancelDeletedWorkflowRuns). The cancel path
   * completes the WorkflowRun by Compare-And-Set FIRST, then calls
   * {@code WorkflowExecutionService.cancelPendingAndRunningTasks}, which resolves the revision
   * with {@code workflowRevisionRepository.findById(...).get()}. A missing revision throws AFTER
   * the run is already terminal, so its in-flight TaskRuns are never cancelled - and because the
   * run is now terminal, {@code cancelDeletedWorkflowRuns} (which pages on in-flight phases) and
   * {@code reapRunsWithMissingRevision} (likewise) never look at it again. The claimed TaskRun is
   * orphaned permanently unless it happens to carry a {@code timeoutAt}.
   */
  @Test
  void cancelWithMissingRevisionOrphansInFlightTasksToday() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("orphan-cancel-wf", RunStatus.running, RunPhase.running);
    wfRun.setWorkflowRevisionRef("revision-that-no-longer-exists");
    workflowRunRepository.save(wfRun);

    TaskRunEntity taskRun =
        savedTaskRun(
            "orphaned-on-cancel",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    assertThrows(
        Exception.class,
        () -> workflowRunService.cancel(wfRun.getId()),
        "current behaviour: cancel throws once the revision no longer resolves");

    // The run is already terminal - the Compare-And-Set ran before the throw.
    WorkflowRunEntity cancelledRun = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
    assertEquals(RunPhase.completed, cancelledRun.getPhase());
    assertEquals(RunStatus.cancelled, cancelledRun.getStatus());

    // ...but its in-flight TaskRun was never touched.
    TaskRunEntity orphan = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(
        RunPhase.running,
        orphan.getPhase(),
        "current behaviour: the in-flight TaskRun is left running by a terminal WorkflowRun");
  }

  /** Tombstone delete must never orphan in-flight work, revision present or not. Fails today. */
  @Test
  @Disabled("E4 gap: cancelPendingAndRunningTasks calls findById(revision).get() with no fallback")
  void cancelWithMissingRevisionShouldStillCancelInFlightTasks() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("orphan-cancel-should-wf", RunStatus.running, RunPhase.running);
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
            () ->
                assertEquals(
                    RunPhase.completed,
                    taskRunRepository.findById(taskRun.getId()).orElseThrow().getPhase()));
  }
}
