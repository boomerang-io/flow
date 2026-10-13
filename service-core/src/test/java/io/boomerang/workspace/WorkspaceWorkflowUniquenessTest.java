package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.workflow.WorkflowService;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Workflow-name uniqueness within a workspace used to be checked with {@code
 * RelationshipService.check()}, which answers "may this caller act on this?", not "does this
 * already exist?" - with no principal on the SecurityContext (as here) it always answers true, so
 * creation failed with the duplicate-name error on every single call. The fix is a pure existence
 * lookup: creation must succeed with no collision and still reject a real duplicate name within
 * the same workspace.
 */
class WorkspaceWorkflowUniquenessTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
  }

  @Test
  void creatingAWorkflowWithAUniqueNameInTheWorkspaceSucceeds() {
    String workspace = createWorkspace("workflow-uniqueness-a");

    Workflow workflow = workflowService.create(workspace, newWorkflow("unique-workflow"));

    assertNotNull(workflow);
    assertEquals("unique-workflow", workflow.getName());
  }

  @Test
  void creatingAWorkflowWithADuplicateNameInTheSameWorkspaceIsRejected() {
    String workspace = createWorkspace("workflow-uniqueness-b");
    workflowService.create(workspace, newWorkflow("duplicate-workflow"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.create(workspace, newWorkflow("duplicate-workflow")));
    assertEquals("WORKFLOW_INVALID_REQ", ex.getReason());
  }

  @Test
  void theSameWorkflowNameInADifferentWorkspaceIsNotADuplicate() {
    String workspaceA = createWorkspace("workflow-uniqueness-c");
    String workspaceB = createWorkspace("workflow-uniqueness-d");
    workflowService.create(workspaceA, newWorkflow("shared-name"));

    Workflow workflow = workflowService.create(workspaceB, newWorkflow("shared-name"));

    assertNotNull(workflow);
    assertEquals("shared-name", workflow.getName());
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private static Workflow newWorkflow(String name) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    return workflow;
  }
}
