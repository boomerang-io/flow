package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.workflow.ActionService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.model.ActionRequest;
import java.util.LinkedList;
import java.util.List;
import org.springframework.data.mongodb.core.query.Update;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Actioner;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * An approval task asks for as many approvers as its declared {@code numberOfApprovals} says, and
 * ends with the five results it declares, which a later task reads like any other task's.
 */
class ApprovalResultsTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private WorkflowService workflowService;
  @Autowired private ActionService actionService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void theDeclaredNameSetsHowManyApproversAreNeeded() {
    assertEquals(2, approversNeeded(new RunParam("numberOfApprovals", "2")));
  }

  @Test
  void theNameReadBeforeIsStillTaken() {
    assertEquals(3, approversNeeded(new RunParam("numberOfApprovers", "3")));
  }

  @Test
  void aBlankDeclaredValueFallsBackToTheNameReadBefore() {
    assertEquals(
        2,
        approversNeeded(
            new RunParam("numberOfApprovals", ""), new RunParam("numberOfApprovers", "2")));
  }

  @Test
  void aLaterTaskReadsTheApprovalsResults() {
    String runId =
        workflowService.submit(approvalWorkflow(), new WorkflowSubmitRequest(), true).getId();
    String approveId =
        awaitEngine("the approval task run exists")
            .until(
                () ->
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("approve", runId)
                        .map(TaskRunEntity::getId)
                        .orElse(null),
                Objects::nonNull);
    Query byApprove = new Query(Criteria.where("taskRunRef").is(approveId));
    ActionEntity action =
        awaitEngine("the approval action is created")
            .until(() -> mongoTemplate.findOne(byApprove, ActionEntity.class), a -> a != null);

    ActionRequest request = new ActionRequest();
    request.setId(action.getId());
    request.setApproved(true);
    request.setComments("ship it");
    actionService.action("approval-results-ws", List.of(request));

    TaskRunEntity after =
        awaitEngine("the task after the approval is admitted")
            .until(
                () -> taskRunRepository.findFirstByNameAndWorkflowRunRef("after", runId).orElse(null),
                t -> t != null && RunStatus.ready.equals(t.getStatus()));
    assertEquals("approved", paramValue(after, "msg"));
    TaskRunEntity approve =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("approve", runId).orElseThrow();
    assertEquals("ship it", resultValue(approve, "approvalComments"));
  }

  @Test
  void eachApprovalIsKeptUntilEnoughHaveBeenGiven() {
    String runId =
        workflowService
            .submit(
                approvalWorkflow(new RunParam("numberOfApprovals", "2")),
                new WorkflowSubmitRequest(),
                true)
            .getId();
    String approveId =
        awaitEngine("the approval task run exists")
            .until(
                () ->
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("approve", runId)
                        .map(TaskRunEntity::getId)
                        .orElse(null),
                Objects::nonNull);
    Query byApprove = new Query(Criteria.where("taskRunRef").is(approveId));
    ActionEntity action =
        awaitEngine("the approval action is created")
            .until(() -> mongoTemplate.findOne(byApprove, ActionEntity.class), a -> a != null);

    actionService.action("approval-results-ws", List.of(approval(action.getId(), "first")));

    ActionEntity afterFirst = mongoTemplate.findById(action.getId(), ActionEntity.class);
    assertEquals(1, afterFirst.getActioners().size(), "the first approval is kept");
    assertEquals(ActionStatus.submitted, afterFirst.getStatus());

    actionService.action("approval-results-ws", List.of(approval(action.getId(), "second")));

    awaitEngine("the approval ends once the second approval lands")
        .until(
            () ->
                taskRunRepository
                    .findFirstByNameAndWorkflowRunRef("approve", runId)
                    .map(TaskRunEntity::getStatus)
                    .orElse(null),
            RunStatus.succeeded::equals);
    assertEquals(
        ActionStatus.approved, mongoTemplate.findById(action.getId(), ActionEntity.class).getStatus());
  }

  @Test
  void anApprovalPastTheRequiredCountIsRefused() {
    String runId =
        workflowService
            .submit(
                approvalWorkflow(new RunParam("numberOfApprovals", "2")),
                new WorkflowSubmitRequest(),
                true)
            .getId();
    String approveId =
        awaitEngine("the approval task run exists")
            .until(
                () ->
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("approve", runId)
                        .map(TaskRunEntity::getId)
                        .orElse(null),
                Objects::nonNull);
    Query byApprove = new Query(Criteria.where("taskRunRef").is(approveId));
    ActionEntity action =
        awaitEngine("the approval action is created")
            .until(() -> mongoTemplate.findOne(byApprove, ActionEntity.class), a -> a != null);
    // Two approvals have landed and the deciding write has not yet: the window approvers acting
    // together fall into.
    Actioner first = new Actioner();
    first.setApproverId("first");
    first.setApproved(true);
    Actioner second = new Actioner();
    second.setApproverId("second");
    second.setApproved(true);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(action.getId())),
        new Update().set("actioners", List.of(first, second)),
        ActionEntity.class);

    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () ->
                actionService.action(
                    "approval-results-ws", List.of(approval(action.getId(), "third"))));

    assertEquals("ACTION_UNABLE_TO_ACTION", refused.getReason());
    assertEquals(
        2, mongoTemplate.findById(action.getId(), ActionEntity.class).getActioners().size());
  }

  private static ActionRequest approval(String actionId, String comments) {
    ActionRequest request = new ActionRequest();
    request.setId(actionId);
    request.setApproved(true);
    request.setComments(comments);
    return request;
  }

  private int approversNeeded(RunParam... params) {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("approver-count-wf-" + System.nanoTime(), RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            "approve",
            TaskType.approval,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    task.setParams(new LinkedList<>(List.of(params)));
    taskRunRepository.save(task);

    taskExecutionService.execute(task.getId());

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(task.getId()));
    return awaitEngine("the approval action is created")
        .until(() -> mongoTemplate.findOne(byTaskRunRef, ActionEntity.class), a -> a != null)
        .getNumberOfApprovers();
  }

  /** start -> approve (approval) -> after (echoes the approval's status) -> end. */
  private String approvalWorkflow(RunParam... approveParams) {
    Task approval = new Task();
    approval.setName("approval-results-approval-" + System.nanoTime());
    approval.setType(TaskType.approval);
    String approvalRef = taskService.create(approval).getId();
    Task echo = new Task();
    echo.setName("approval-results-echo-" + System.nanoTime());
    echo.setType(TaskType.template);
    echo.getSpec().setImage("busybox:latest");
    echo.getSpec().setCommand(List.of("echo"));
    String echoRef = taskService.create(echo).getId();

    Workflow workflow = new Workflow();
    workflow.setName("approval-results-" + System.nanoTime());
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                node("start", TaskType.start, null, null, List.of()),
                node("approve", TaskType.approval, approvalRef, "start", List.of(approveParams)),
                node(
                    "after",
                    TaskType.template,
                    echoRef,
                    "approve",
                    List.of(new RunParam("msg", "$(tasks.approve.results.approvalStatus)"))),
                node("end", TaskType.end, null, "after", List.of()))));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private static WorkflowTask node(
      String name, TaskType type, String taskRef, String dependsOn, List<RunParam> params) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    task.setParams(new LinkedList<>(params));
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }

  private static Object paramValue(TaskRunEntity taskRun, String name) {
    return taskRun.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .map(RunParam::getValue)
        .findFirst()
        .orElse(null);
  }

  private static Object resultValue(TaskRunEntity taskRun, String name) {
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .findFirst()
        .orElse(null);
  }
}
