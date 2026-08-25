package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * F3 fix: a retry must record the retried run's REAL owning workspace, not the workspace named in
 * the request path.
 *
 * <p>The collapsed {@code api.WorkspaceWorkflowRunService.retry} wrote {@code
 * createNodeAndEdge(WORKSPACE, team, HAS_WORKFLOWRUN, ...)} straight off the {@code
 * /workspace/&#123;team&#125;/workflowrun/&#123;id&#125;/retry} path segment. That segment only has
 * to pass {@link io.boomerang.core.RelationshipService#check} - and {@code check} returns {@code
 * true} unconditionally for a global-scope token (RelationshipService:417-419), which is exactly
 * the identity these tests run under. Retrying through another workspace's URL therefore wrote a
 * permanent, wrong ownership edge into the relationship graph.
 */
class WorkflowRunRetryOwnerTest extends AbstractEngineIntegrationTest {

  private static final String OWNING_WORKSPACE = "retry-owner-owning-ws";
  private static final String OTHER_WORKSPACE = "retry-owner-other-ws";

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;

  @Test
  void retryRecordsTheRunsOwningWorkspaceNotTheWorkspaceInThePath() {
    seedRelationshipRoot();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OWNING_WORKSPACE, OWNING_WORKSPACE, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OTHER_WORKSPACE, OTHER_WORKSPACE, Optional.empty());

    String workflowId = createLinearWorkflow("retry-owner");
    // The Workflow and its run both belong to OWNING_WORKSPACE - the edges
    // WorkspaceWorkflowService.create/submit write in production.
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        OWNING_WORKSPACE,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowId,
        workflowId,
        Optional.empty(),
        Optional.empty());
    String wfRunId =
        workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        OWNING_WORKSPACE,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        wfRunId,
        wfRunId,
        Optional.empty(),
        Optional.empty());

    // Retry through the OTHER workspace's URL. The global identity passes the check either way.
    WorkflowRun retried = workflowRunService.retry(OTHER_WORKSPACE, wfRunId).getBody();

    String recordedOwner =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOWRUN, RelationshipType.WORKFLOWRUN, retried.getId());
    assertEquals(
        OWNING_WORKSPACE,
        recordedOwner,
        "the retry must be owned by the source run's workspace, not the path's");
    assertNotEquals(
        OTHER_WORKSPACE, recordedOwner, "the path workspace must never become the recorded owner");
  }

  @Test
  void retryFallsBackToTheWorkflowsWorkspaceWhenTheSourceRunHasNoOwnershipEdge() {
    seedRelationshipRoot();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OWNING_WORKSPACE, OWNING_WORKSPACE, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OTHER_WORKSPACE, OTHER_WORKSPACE, Optional.empty());

    String workflowId = createLinearWorkflow("retry-owner-fallback");
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        OWNING_WORKSPACE,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowId,
        workflowId,
        Optional.empty(),
        Optional.empty());
    // No HAS_WORKFLOWRUN edge - this is what the engine's auto-retry (WorkflowExecutionService
    // .timeoutWorkflow) leaves behind, so a user retry of such a run has no parent to inherit.
    String wfRunId =
        workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();

    WorkflowRun retried = workflowRunService.retry(OTHER_WORKSPACE, wfRunId).getBody();

    assertEquals(
        OWNING_WORKSPACE,
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOWRUN, RelationshipType.WORKFLOWRUN, retried.getId()),
        "with no run-level owner the Workflow's workspace is the owner, never the path's");
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
            workflowTask("a", TaskType.template, templateId, "start"),
            workflowTask("end", TaskType.end, null, "a")));
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
