package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Approval Action creation is idempotent per TaskRun: the execution-entry Compare-And-Set admits
 * exactly one dispatch into running, so a duplicate dispatch of one approval TaskRun (which an
 * agent claim race used to produce) creates exactly one approval record for a single gate. And
 * the gate arms its wait fenced on still running, so a cancel that lands first is never undone.
 */
class ApprovalActionIdempotencyTest extends AbstractSpiedEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void duplicateDispatchCreatesExactlyOneApprovalAction() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("duplicate-action-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "approval-gate",
                TaskType.approval,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();

    // Two dispatches of the SAME TaskRun - exactly what a claim race would produce. execute() is
    // @Async behind the proxy, so both run like real duplicate dispatches; the loser must no-op.
    taskExecutionService.execute(taskRunId);
    taskExecutionService.execute(taskRunId);

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(taskRunId));
    awaitEngine("the winning dispatch to create the single ActionEntity")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    // Hold the assertion through a settle window so a late duplicate insert would be caught.
    awaitEngine("no duplicate ActionEntity to appear")
        .during(Duration.ofSeconds(2))
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    assertEquals(1, mongoTemplate.count(byTaskRunRef, ActionEntity.class));
    assertEquals(
        RunStatus.waiting,
        taskRunRepository.findById(taskRunId).orElseThrow().getStatus(),
        "the approval gate should be waiting on user action");
  }

  /**
   * The cancel lands between the execution-entry Compare-And-Set and the arm - the seam a run
   * cancel or a timeout reap crosses in production. Arming used to save the handler's entity whole,
   * putting waiting and running back over the completion, and the approval stayed open on a gate
   * nobody could answer.
   */
  @Test
  void cancelLandingBeforeTheArmIsKeptAndLeavesNoAction() throws InterruptedException {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("cancel-before-arm-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "approval-gate",
                TaskType.approval,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();

    doAnswer(
            invocation -> {
              Object started = invocation.callRealMethod();
              if (started != null) {
                taskRunService.tryComplete(
                    taskRunRepository.findById(taskRunId).orElseThrow(),
                    RunStatus.cancelled,
                    "WorkflowRun cancelled");
              }
              return started;
            })
        .when(spiedTaskRunService)
        .tryStartExecution(any(), any(), any());

    // The handler's last act is the run's idle check, so the arm and its outcome are stored once
    // that returns.
    CountDownLatch handlerDone = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object idle = invocation.callRealMethod();
              handlerDone.countDown();
              return idle;
            })
        .when(spiedTaskRunService)
        .allInFlightWaiting(wfRun.getId());

    taskExecutionService.execute(taskRunId);

    assertTrue(handlerDone.await(60, TimeUnit.SECONDS), "the handler reaches its idle check");
    TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunPhase.completed, after.getPhase(), "the arm must not undo the completion");
    assertEquals(RunStatus.cancelled, after.getStatus());
    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(taskRunId));
    assertEquals(
        0,
        mongoTemplate.count(byTaskRunRef, ActionEntity.class),
        "a gate that completed before it armed waits on no action");
    assertFalse(
        workflowRunRepository.findById(wfRun.getId()).orElseThrow().isAwaitingApproval(),
        "the run is not awaiting an approval that does not exist");
  }
}
