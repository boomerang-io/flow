package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Q-127 residue — handler paths that still write a WHOLE stale document instead of a
 * Compare-And-Set, so a transition another caller already committed is silently rolled back.
 *
 * <p>Both tests drive the REAL handler. The concurrent writer is injected at a seam the handler
 * genuinely crosses (a database round trip between its entry read and its save), which is exactly
 * where a second instance's write lands in production; nothing about the handler is stubbed out.
 */
class StaleSnapshotSaveTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private WorkflowRunService workflowRunService;

  @MockitoSpyBean private DAGUtility dagUtility;
  @MockitoSpyBean private WorkflowRunRepository spiedWorkflowRunRepository;

  /**
   * Q-127 #6/#11 — the admission Compare-And-Set was supposed to make a join queued by both
   * parents harmless. It does on the {@code canRunTask == true} branch ({@code
   * TaskExecutionService.java:153}); the {@code else} branch at {@code
   * TaskExecutionService.java:169-171} never got one. It writes the whole entity read at handler
   * entry ({@code :91}) back as {@code skipped}, so a TaskRun the other parent already admitted
   * and started is reverted to {@code pending}, loses its {@code startTime}/{@code timeoutAt}, and
   * is then completed as {@code skipped} by the {@code end()} that follows.
   */
  @Test
  void queueSkipPathMustNotOverwriteATaskRunAnotherCallerAlreadyStarted() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("skip-path-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity join =
        savedTaskRun(
            "join",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    String joinId = join.getId();

    // The other parent's queue() wins admission and the task starts, landing between this
    // caller's entry read and its skip-path save.
    doAnswer(
            invocation -> {
              taskRunService.tryAdmit(joinId, List.of());
              taskRunService.tryStartExecution(joinId, new Date(), 0L);
              return false;
            })
        .when(dagUtility)
        .canRunTask(anyList(), any());

    taskExecutionService.queue(joinId);

    awaitEngine("queue() to finish its skip path")
        .until(() -> !RunStatus.notstarted.equals(current(joinId).getStatus()));

    TaskRunEntity after = current(joinId);
    assertEquals(
        RunStatus.running,
        after.getStatus(),
        "a TaskRun another caller already admitted and started must not be overwritten as skipped");
    assertEquals(RunPhase.running, after.getPhase(), "the started phase must not be rolled back");
    assertNotNull(after.getStartTime(), "the start time written by tryStartExecution was lost");
  }

  /**
   * {@code setwfstatus} is the one task type that still writes the WorkflowRun with a full-document
   * save ({@code TaskExecutionService.java:588-595}) using the entity read at {@code execute()}
   * entry ({@code :257}). Every field a concurrent Compare-And-Set wrote in between — a result
   * pushed by a parallel {@code setwfproperty}, {@code isAwaitingApproval}, {@code
   * pauseRequestedAt}, {@code phase}/{@code status} — is rolled back. Not one of the audit's 31
   * handlers; the same F1 defect, missed.
   */
  @Test
  void setWorkflowStatusMustNotRollBackConcurrentWorkflowRunWrites() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("setwfstatus-wf", RunStatus.running, RunPhase.running);
    String wfRunId = wfRun.getId();
    TaskRunEntity task =
        savedTaskRun(
            "set-status",
            TaskType.setwfstatus,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRunId);
    task.setParams(List.of(new RunParam("status", "failed", ParamType.string)));
    taskRunRepository.save(task);

    // A parallel branch's setwfproperty pushes a result just after execute() read the run.
    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Optional<WorkflowRunEntity> read =
                  (Optional<WorkflowRunEntity>) invocation.callRealMethod();
              if (wfRunId.equals(invocation.getArgument(0)) && injected.compareAndSet(false, true)) {
                workflowRunService.appendResult(wfRunId, new RunResult("artifact", "build-42"));
              }
              return read;
            })
        .when(spiedWorkflowRunRepository)
        .findById(any());

    taskExecutionService.execute(task.getId());

    awaitEngine("the setwfstatus task to complete")
        .until(() -> RunPhase.completed.equals(current(task.getId()).getPhase()));

    List<RunResult> results = workflowRunRepository.findById(wfRunId).orElseThrow().getResults();
    assertEquals(
        1,
        results.stream().filter(r -> "artifact".equals(r.getName())).count(),
        "the concurrently appended WorkflowRun result was rolled back by the full-document save;"
            + " got "
            + results);
  }

  private TaskRunEntity current(String id) {
    return taskRunRepository.findById(id).orElseThrow();
  }
}
