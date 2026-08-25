package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.repository.ActionRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cancelling a WorkflowRun through the workspace surface must also close the Actions (approval /
 * manual tasks) it left open.
 *
 * <p>F3 deleted {@code api.WorkspaceActionService.cancelAllByWorkflowRun} - a one-line delegation
 * with this as its only caller - and inlined {@code
 * ActionRepository.updateStatusByWorkflowRunRef(runId, ActionStatus.cancelled)} into {@link
 * WorkflowRunService#cancel(String, String)}. No test referenced either the repository method or
 * {@code ActionStatus.cancelled}, so the close-out could have been dropped in the move with nothing
 * noticing. This pins it, including that it does not reach past the cancelled run.
 *
 * <p>The identity is the base class's global token: the workspace guard on this method has its own
 * coverage in {@link WorkflowRunWorkspaceAuthorizationTest}, and the run here has to be built
 * through the real create/submit/start path (a synthetic run has no {@code workflowRevisionRef},
 * which {@code WorkflowExecutionService.cancelPendingAndRunningTasks} dereferences).
 */
class WorkflowRunCancelActionsTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "wfrun-cancel-ws";

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private ActionRepository actionRepository;

  @Test
  void cancelClosesTheRunsOpenActionsAndLeavesAnotherRunsAlone() {
    seedRelationshipRoot();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, Optional.empty());

    String workflowId = createLinearWorkflow("wfrun-cancel");

    String cancelledRunId = ownedRun(workflowId);
    workflowRunService.start(cancelledRunId, Optional.empty());
    awaitEngine("the template TaskRun to be ready, so the run is genuinely in flight")
        .untilAsserted(
            () -> {
              Optional<TaskRunEntity> task =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", cancelledRunId);
              assertTrue(task.isPresent());
              assertEquals(RunStatus.ready, task.get().getStatus());
            });
    String openActionId = savedAction(workflowId, cancelledRunId).getId();

    // A second run of the same Workflow, never cancelled: its Action must survive untouched.
    String bystanderRunId = ownedRun(workflowId);
    String bystanderActionId = savedAction(workflowId, bystanderRunId).getId();

    workflowRunService.cancel(WORKSPACE, cancelledRunId);

    assertEquals(
        RunStatus.cancelled,
        workflowRunRepository.findById(cancelledRunId).orElseThrow().getStatus(),
        "the run itself must be cancelled - otherwise the Action assertion proves nothing");
    assertEquals(
        ActionStatus.cancelled,
        actionRepository.findById(openActionId).orElseThrow().getStatus(),
        "the cancelled run's open Action must be closed as cancelled");
    assertEquals(
        ActionStatus.submitted,
        actionRepository.findById(bystanderActionId).orElseThrow().getStatus(),
        "another run's Action must be untouched");
  }

  private String ownedRun(String workflowId) {
    String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        WORKSPACE,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        runId,
        runId,
        Optional.empty(),
        Optional.empty());
    return runId;
  }

  private ActionEntity savedAction(String workflowRef, String workflowRunRef) {
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflowRef);
    action.setWorkflowRunRef(workflowRunRef);
    action.setTaskRunRef(workflowRunRef + "-task");
    action.setType(ActionType.approval);
    action.setStatus(ActionStatus.submitted);
    action.setCreationDate(new Date());
    action.setNumberOfApprovers(1);
    return actionRepository.save(action);
  }

  private String createLinearWorkflow(String name) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask("echo", TaskType.template, templateId, "start"),
            workflowTask("end", TaskType.end, null, "echo")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }
}
