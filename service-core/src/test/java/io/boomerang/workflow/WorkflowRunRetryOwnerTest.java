package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
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
  private static final List<RunPhase> ALL_PHASES = List.of(RunPhase.values());

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

  /**
   * The owner must be resolved BEFORE the retried run is created, so an unresolvable one cannot
   * fail the request after the fact.
   *
   * <p>Previously {@code createNodeAndEdge(WORKSPACE, "", ...)} ran after {@code retry} had already
   * saved and queued the clone, so a graph-orphaned Workflow produced {@code
   * IllegalArgumentException: Node does not exist: workspace:} - an unmapped 500 - while the run
   * executed anyway with no owner. Both halves are asserted: the mapped error, and that nothing was
   * created.
   */
  @Test
  void retryIsRefusedWithAMappedErrorAndCreatesNothingWhenNoWorkspaceOwnsTheRun() {
    seedRelationshipRoot();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OTHER_WORKSPACE, OTHER_WORKSPACE, Optional.empty());

    // A graph-orphaned Workflow: no HAS_WORKFLOW edge, so its runs have no owner to inherit and
    // no HAS_WORKFLOWRUN edge of their own either. Reachable because the identity here is
    // global-scope, for which RelationshipService.check returns true for any path workspace.
    String workflowId = createLinearWorkflow("retry-owner-orphan");
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowRunService.retry(OTHER_WORKSPACE, wfRunId),
            "an unresolvable owner must surface as a mapped domain error, not IllegalArgumentException");
    assertEquals(
        "TEAM_INVALID_REF", ex.getReason(), "the failure must name the unresolvable workspace");

    assertEquals(
        List.of(wfRunId),
        workflowRunRepository.findByWorkflowRefAndPhaseIn(workflowId, ALL_PHASES).stream()
            .map(WorkflowRunEntity::getId)
            .toList(),
        "the retry must fail before the clone is created, leaving only the source run");
  }

  /**
   * The engine's auto-retry calls the unscoped {@code retry(workflowRunId, start, retryCount)}
   * directly (WorkflowExecutionService), which used to create the clone WITHOUT a HAS_WORKFLOWRUN
   * edge - the run appeared in {@code /query} (filter walks the Workflow) but {@code GET
   * /&#123;id&#125;} was denied (check anchors on the run). Ownership is now written by the
   * unscoped method itself, so user retries and engine auto-retries produce identically-owned
   * runs through one path.
   */
  @Test
  void anUnscopedRetryRecordsTheSameOwnerAsAUserRetry() {
    seedRelationshipRoot();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, OWNING_WORKSPACE, OWNING_WORKSPACE, Optional.empty());

    String workflowId = createLinearWorkflow("retry-owner-engine");
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

    // The engine path: no workspace segment, no scoped wrapper.
    WorkflowRun retried = workflowRunService.retry(wfRunId, false, 1);

    assertEquals(
        OWNING_WORKSPACE,
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOWRUN, RelationshipType.WORKFLOWRUN, retried.getId()),
        "an engine auto-retry must record the same owner a user retry records");
  }

  /**
   * The deliberate asymmetry with the scoped path: a user retry of an unowned run refuses
   * (TEAM_INVALID_REF, nothing created - the test above this one), but the engine's auto-retry
   * must never fail a run's recovery over graph bookkeeping, so an unresolvable owner logs and
   * creates the clone ownerless - exactly what the engine path produced before for EVERY retry.
   */
  @Test
  void anUnscopedRetryOfAnUnownedRunStillRetriesAndStaysOwnerless() {
    seedRelationshipRoot();

    // Graph-orphaned: no HAS_WORKFLOW edge and no HAS_WORKFLOWRUN edge.
    String workflowId = createLinearWorkflow("retry-owner-engine-orphan");
    String wfRunId =
        workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();

    WorkflowRun retried = workflowRunService.retry(wfRunId, false, 1);

    assertEquals(
        "",
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOWRUN, RelationshipType.WORKFLOWRUN, retried.getId()),
        "the orphan engine retry stays ownerless rather than failing or inventing an owner");
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
