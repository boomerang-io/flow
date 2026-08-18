package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.api.WorkspaceWorkflowService;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Workflow-name uniqueness within a workspace used to be checked with {@code
 * RelationshipService.check()}, which answers "may this caller act on this?", not "does this
 * already exist?" - with no principal on the SecurityContext (as here) it always answers true, so
 * creation failed with WORKFLOW_INVALID_REF on every single call. The fix is a pure existence
 * lookup: creation must succeed with no collision and still reject a real duplicate name within
 * the same workspace.
 */
class WorkspaceWorkflowUniquenessTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceWorkflowService workspaceWorkflowService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedFeatureQuotaSettingDisabled();
  }

  @Test
  void creatingAWorkflowWithAUniqueNameInTheWorkspaceSucceeds() {
    String workspace = createWorkspace("workflow-uniqueness-a");

    Workflow workflow = workspaceWorkflowService.create(workspace, newWorkflow("unique-workflow"));

    assertNotNull(workflow);
    assertEquals("unique-workflow", workflow.getName());
  }

  @Test
  void creatingAWorkflowWithADuplicateNameInTheSameWorkspaceIsRejected() {
    String workspace = createWorkspace("workflow-uniqueness-b");
    workspaceWorkflowService.create(workspace, newWorkflow("duplicate-workflow"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workspaceWorkflowService.create(workspace, newWorkflow("duplicate-workflow")));
    assertEquals("WORKFLOW_INVALID_REFERENCE", ex.getReason());
  }

  @Test
  void theSameWorkflowNameInADifferentWorkspaceIsNotADuplicate() {
    String workspaceA = createWorkspace("workflow-uniqueness-c");
    String workspaceB = createWorkspace("workflow-uniqueness-d");
    workspaceWorkflowService.create(workspaceA, newWorkflow("shared-name"));

    Workflow workflow = workspaceWorkflowService.create(workspaceB, newWorkflow("shared-name"));

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

  // setUpWorkspaceDefaults/canCreateWithQuotas both read "features"."teamQuotas" unconditionally
  // - the loader normally seeds it, but this shared Testcontainers Mongo starts empty.
  private void seedFeatureQuotaSettingDisabled() {
    if (settingsRepository.findOneByKey("features") != null) {
      return;
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey("features");
    settings.setName("Features");
    SettingConfig teamQuotas = new SettingConfig();
    teamQuotas.setKey("teamQuotas");
    teamQuotas.setType("boolean");
    teamQuotas.setValue("false");
    settings.setConfig(List.of(teamQuotas));
    settingsRepository.save(settings);
  }
}
