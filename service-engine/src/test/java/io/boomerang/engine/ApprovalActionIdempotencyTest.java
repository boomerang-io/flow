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
 * Approval Action creation must be idempotent per TaskRun. Passes today by demonstrating the
 * current defect: execute() has no admission guard and createActionTask always inserts, so a
 * duplicate dispatch of one approval TaskRun (which the agent-queue claim race produces) yields
 * duplicate approval records for a single gate. Once a unique actions(taskRunRef) index and an
 * execution admission guard land, invert: exactly one ActionEntity.
 */
class ApprovalActionIdempotencyTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private MongoTemplate mongoTemplate;

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

    // Two dispatches of the SAME TaskRun - exactly what the claim race produces. execute() is
    // @Async behind the proxy, so both run like real duplicate dispatches.
    taskExecutionService.execute(taskRunId);
    taskExecutionService.execute(taskRunId);

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(taskRunId));
    awaitEngine("both executes to create their ActionEntity")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) >= 2);
    assertEquals(
        2,
        mongoTemplate.count(byTaskRunRef, ActionEntity.class),
        "DEFECT: each execute() of the same approval TaskRun creates a new ActionEntity -"
            + " duplicate approvals for one gate. When the unique actions(taskRunRef) index and"
            + " execution admission guard land, flip this to expect exactly 1.");
  }
}
