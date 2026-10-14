package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WorkflowService.applyWorkspaceDefaults settles a Workflow's workspace on every save, create and
 * update alike: an authored workspace survives intact, a blank size takes the storage quota, a
 * blank mountPath takes the default, the size is never stripped of its Kubernetes unit, a legacy
 * bare-number size is read as Gi, and - with quotas enforced - a size over quota is refused. A
 * revision carrying the v3 workflow-storage marker cannot be run until it is saved again.
 */
class WorkflowWorkspaceSpecDefaultsTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  private static final String TASK_SLUG = "workspace-defaults-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    // Workspace quotas off, so applyWorkspaceDefaults takes no quota path unless a test says so.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @AfterEach
  void resetQuotaFeature() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void anAuthoredWorkspaceSurvivesSaveIntact() {
    String workspace = createWorkspace("workspace-spec-intact");

    Workflow created =
        workflowService.create(
            workspace, workflowWithWorkspace(workspace("1Gi", "/workspace/run", "ReadWriteOnce")));

    assertEquals("1Gi", created.getWorkspace().getSize());
    assertEquals("ReadWriteOnce", created.getWorkspace().getAccessMode());
    assertEquals("/workspace/run", created.getWorkspace().getMountPath());
  }

  @Test
  void aBlankSizeTakesTheQuotaAndABlankMountPathTakesTheDefault() {
    String workspace = createWorkspace("workspace-spec-blank");

    Workflow created =
        workflowService.create(workspace, workflowWithWorkspace(workspace(null, " ", null)));

    // seed/settings.json sets max.workspace.storage to 2Gi.
    assertEquals("2Gi", created.getWorkspace().getSize());
    assertEquals(WorkflowWorkspace.DEFAULT_MOUNT_PATH, created.getWorkspace().getMountPath());
  }

  @Test
  void aWorkflowWithoutAWorkspaceHasNone() {
    String workspace = createWorkspace("workspace-spec-none");

    Workflow created = workflowService.create(workspace, workflowWithWorkspace(null));

    assertNull(created.getWorkspace(), "presence is the switch: nothing is defaulted in");
  }

  @Test
  void aSizeOverQuotaIsRejected() {
    setFeatureSetting(QUOTA_FEATURE, true);
    String workspace = createWorkspace("workspace-spec-over-quota");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, workflowWithWorkspace(workspace("5Gi", null, null))));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  @Test
  void aBareNumberLegacySizeIsInterpretedAsGi() {
    setFeatureSetting(QUOTA_FEATURE, true);
    String workspace = createWorkspace("workspace-spec-legacy-size");

    // A bare "5" must be read as 5Gi (over the 2Gi quota), not 5 bytes (never over quota).
    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, workflowWithWorkspace(workspace("5", null, null))));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  @Test
  void anUpdateAppliesTheSameDefaultsAndQuota() {
    String workspace = createWorkspace("workspace-spec-update");
    Workflow workflow = workflowWithWorkspace(workspace("1Gi", "/workspace/run", null));
    workflowService.create(workspace, workflow);

    // Blank size and mountPath are settled on the update exactly as they are on create.
    workflow.setWorkspace(workspace(null, "", null));
    Workflow updated = workflowService.apply(workspace, workflow, false);
    assertEquals("2Gi", updated.getWorkspace().getSize());
    assertEquals(WorkflowWorkspace.DEFAULT_MOUNT_PATH, updated.getWorkspace().getMountPath());

    // And the quota gate holds on the update too.
    setFeatureSetting(QUOTA_FEATURE, true);
    workflow.setWorkspace(workspace("5Gi", null, null));
    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowService.apply(workspace, workflow, false));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  @Test
  void aLegacyWorkflowStorageMarkerRefusesSubmitUntilTheWorkflowIsSavedAgain() {
    String workspace = createWorkspace("workspace-spec-legacy-marker");
    Workflow workflow = workflowWithWorkspace(workspace("1Gi", null, null));
    workflowService.create(workspace, workflow);
    String workflowId = workflowIdOf(workspace, workflow.getName());

    // What the v3 upgrade leaves on a revision whose workflow had workflow-level storage.
    WorkflowRevisionEntity revision =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId).orElseThrow();
    revision.setLegacyWorkflowStorage(true);
    workflowRevisionRepository.save(revision);
    assertTrue(
        workflowService.get(workspace, workflow.getName(), Optional.empty(), false)
            .getLegacyWorkflowStorage(),
        "the marker is visible on the model");

    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workspace, workflow.getName(), manualSubmit(), false));
    assertEquals("WORKFLOW_STORAGE_REMOVED", refused.getReason());

    // Saving the Workflow is the acknowledgement: the new revision carries no marker.
    workflowService.apply(workspace, workflow, false);
    WorkflowRevisionEntity saved =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId).orElseThrow();
    assertNull(saved.getLegacyWorkflowStorage(), "a save never copies the marker forward");
    assertNotNull(
        workflowService.submit(workspace, workflow.getName(), manualSubmit(), false).getId());
  }

  private String workflowIdOf(String workspace, String name) {
    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)),
            false);
    assertEquals(1, refs.size(), "the workflow is owned by its workspace");
    return refs.get(0);
  }

  private static WorkflowSubmitRequest manualSubmit() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private static WorkflowWorkspace workspace(String size, String mountPath, String accessMode) {
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setSize(size);
    ws.setMountPath(mountPath);
    ws.setAccessMode(accessMode);
    return ws;
  }

  private static Workflow workflowWithWorkspace(WorkflowWorkspace workspace) {
    Workflow workflow = runnableWorkflow("workflow-with-workspace-" + System.nanoTime(), TASK_SLUG);
    workflow.setWorkspace(workspace);
    return workflow;
  }
}
