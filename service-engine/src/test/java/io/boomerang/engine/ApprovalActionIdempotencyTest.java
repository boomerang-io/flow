package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Approval Action creation is idempotent per TaskRun: the execution-entry Compare-And-Set admits
 * exactly one dispatch into running, so a duplicate dispatch of one approval TaskRun (which an
 * agent claim race used to produce) creates exactly one approval record for a single gate.
 */
class ApprovalActionIdempotencyTest extends AbstractEngineIntegrationTest {

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
}
