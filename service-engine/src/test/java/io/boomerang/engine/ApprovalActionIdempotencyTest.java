package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Approval Action creation must be idempotent per TaskRun. RED-LINE: flips in E4 (B6; E3 adds the
 * unique actions(taskRunRef) index). Passes today by demonstrating the current defect
 * (idempotency-audit.md #12/#8): execute() has no admission guard and createActionTask always
 * inserts, so a duplicate dispatch of one approval TaskRun (which the agent-queue claim race
 * produces) yields duplicate approval records for a single gate. When E3+E4 land, invert: exactly
 * one ActionEntity.
 */
class ApprovalActionIdempotencyTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private MongoTemplate mongoTemplate;

  // RED-LINE: flips in E4 (B6 idempotency key: unique actions(taskRunRef) + find-before-create).
  @Test
  void duplicateDispatchCreatesDuplicateApprovalActions() {
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

    // Two dispatches of the SAME TaskRun, each with its own stale snapshot - exactly what the
    // claim race produces. execute() is @Async, so both run like real duplicate dispatches.
    taskExecutionService.execute(
        taskRunRepository.findById(taskRunId).orElseThrow(),
        workflowRunRepository.findById(wfRun.getId()).orElseThrow());
    taskExecutionService.execute(
        taskRunRepository.findById(taskRunId).orElseThrow(),
        workflowRunRepository.findById(wfRun.getId()).orElseThrow());

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(taskRunId));
    awaitEngine("both executes to create their ActionEntity")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) >= 2);
    assertEquals(
        2,
        mongoTemplate.count(byTaskRunRef, ActionEntity.class),
        "DEFECT (audit #12/#8): each execute() of the same approval TaskRun creates a new"
            + " ActionEntity - duplicate approvals for one gate. When E3's unique index + E4's"
            + " execution CAS land, flip this to expect exactly 1.");
  }
}
