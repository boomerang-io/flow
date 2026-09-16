package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A workflow node other than start/end must name the Task it runs. Without the reference the save
 * path has nothing to resolve (it used to dereference a null task reference and answer 500) and
 * the DAG has nothing to execute, so both the save and the submit reject it by name.
 */
class WorkflowTaskRefValidationTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-ref-validation-ws";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkflowRepository workflowRepository;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    // The workspace needs its edge from root, not just a node: the relationship walk for a global
    // principal starts at root, so a workspace with no incoming edge owns nothing it can reach.
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        WORKSPACE,
        WORKSPACE,
        Optional.empty(),
        Optional.empty());
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask("task-ref-validation-task");
  }

  @Test
  void creatingAWorkflowWithANodeThatHasNoTaskRefIsRejected() {
    Workflow workflow = workflowWithNodeTaskRef("task-ref-validation-create-wf", null);

    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));

    assertEquals("WORKFLOW_MISSING_TASK_REF", ex.getReason());
    assertEquals(400, ex.getStatus().value());
    assertTrue(List.of(ex.getArgs()).contains("work"), "the exception must name the node");
  }

  @Test
  void creatingAWorkflowWithABlankTaskRefIsRejected() {
    Workflow workflow = workflowWithNodeTaskRef("task-ref-validation-blank-wf", "  ");

    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));

    assertEquals("WORKFLOW_MISSING_TASK_REF", ex.getReason());
  }

  @Test
  void updatingAWorkflowToRemoveANodesTaskRefIsRejected() {
    Workflow workflow =
        workflowWithNodeTaskRef("task-ref-validation-apply-wf", "task-ref-validation-task");
    workflowService.create(WORKSPACE, workflow);

    Workflow update = workflowWithNodeTaskRef("task-ref-validation-apply-wf", null);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowService.apply(WORKSPACE, update, false));

    assertEquals("WORKFLOW_MISSING_TASK_REF", ex.getReason());
  }

  /*
   * A workflow saved before the save-time check can still carry a node with no reference, so the
   * submit re-checks the stored revision rather than trusting the save.
   */
  @Test
  void submittingAStoredWorkflowWhoseNodeLostItsTaskRefIsRejected() {
    Workflow workflow =
        workflowWithNodeTaskRef("task-ref-validation-submit-wf", "task-ref-validation-task");
    Workflow created = workflowService.create(WORKSPACE, workflow);
    // create() nulls the id on the way out, and the name is unique to this test.
    String workflowRef =
        workflowRepository.findAll().stream()
            .filter(entity -> created.getName().equals(entity.getName()))
            .findFirst()
            .orElseThrow()
            .getId();

    WorkflowRevisionEntity revision =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowRef).orElseThrow();
    revision.getTasks().stream()
        .filter(t -> "work".equals(t.getName()))
        .forEach(t -> t.setTaskRef(null));
    workflowRevisionRepository.save(revision);

    // The unscoped submit is where the guard sits and where the workspace-scoped route funnels
    // into (WorkflowService.submit(team, name, ...) resolves the ref and calls this).
    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workflowRef, new WorkflowSubmitRequest(), false));

    assertEquals("WORKFLOW_MISSING_TASK_REF", ex.getReason());
    assertTrue(List.of(ex.getArgs()).contains("work"), "the exception must name the node");
  }

  private static Workflow workflowWithNodeTaskRef(String name, String taskRef) {
    Workflow workflow = new Workflow();
    workflow.setName(name);

    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);

    WorkflowTask work = new WorkflowTask();
    work.setName("work");
    work.setType(TaskType.template);
    work.setTaskRef(taskRef);
    WorkflowTaskDependency dependsOnStart = new WorkflowTaskDependency();
    dependsOnStart.setTaskRef("start");
    work.setDependencies(new LinkedList<>(List.of(dependsOnStart)));

    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency dependsOnWork = new WorkflowTaskDependency();
    dependsOnWork.setTaskRef("work");
    end.setDependencies(new LinkedList<>(List.of(dependsOnWork)));

    workflow.setTasks(new LinkedList<>(List.of(start, work, end)));
    return workflow;
  }
}
