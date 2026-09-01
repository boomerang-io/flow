package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.common.model.WorkflowWorkspaceSpec;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * WorkflowService.setUpWorkspaceDefaults reads a Workspace's spec as a raw {@code Object} - a
 * {@link LinkedHashMap} when the request arrived over JSON, which is what a real Workflow save
 * looks like on the wire. BeanUtils.copyProperties copies nothing from a Map, so an authored spec
 * used to be silently discarded (accessMode/className/mountPath lost, size replaced by the quota
 * default). This pins the ObjectMapper.convertValue fix: the authored spec survives intact, the
 * size is never stripped of its Kubernetes unit, and a legacy bare-number size is read as Gi.
 */
class WorkflowWorkspaceSpecDefaultsTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    // setUpWorkspaceDefaults reads "features"."workspaceQuotas" unconditionally - the loader
    // normally seeds it, but this shared Testcontainers Mongo starts empty.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @AfterEach
  void resetQuotaFeature() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void anAuthoredWorkspaceSpecSurvivesSaveIntact() {
    String workspace = createWorkspace("workspace-spec-intact");
    Map<String, Object> rawSpec = new LinkedHashMap<>();
    rawSpec.put("size", "1Gi");
    rawSpec.put("accessMode", "ReadWriteOnce");
    rawSpec.put("mountPath", "/workspace/run");

    Workflow created =
        workflowService.create(workspace, workflowWithWorkspace("workflowrun", rawSpec));

    WorkflowWorkspaceSpec spec = specOf(created);
    assertEquals("1Gi", spec.getSize());
    assertEquals("ReadWriteOnce", spec.getAccessMode());
    assertEquals("/workspace/run", spec.getMountPath());
  }

  @Test
  void aSizeOverQuotaIsRejected() {
    setFeatureSetting(QUOTA_FEATURE, true);
    String workspace = createWorkspace("workspace-spec-over-quota");
    Map<String, Object> rawSpec = new LinkedHashMap<>();
    // seedTeamQuotaSettings sets max.workflowrun.storage to 2Gi.
    rawSpec.put("size", "5Gi");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, workflowWithWorkspace("workflowrun", rawSpec)));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  @Test
  void aBareNumberLegacySizeIsInterpretedAsGi() {
    setFeatureSetting(QUOTA_FEATURE, true);
    String workspace = createWorkspace("workspace-spec-legacy-size");
    Map<String, Object> rawSpec = new LinkedHashMap<>();
    // seedTeamQuotaSettings sets max.workflowrun.storage to 2Gi - a bare "5" must be read as 5Gi
    // (over quota), not 5 bytes (which would never be over quota).
    rawSpec.put("size", "5");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, workflowWithWorkspace("workflowrun", rawSpec)));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  private WorkflowWorkspaceSpec specOf(Workflow workflow) {
    WorkflowWorkspace ws = workflow.getWorkspaces().get(0);
    return objectMapper.convertValue(ws.getSpec(), WorkflowWorkspaceSpec.class);
  }

  private String createWorkspace(String name) {
    io.boomerang.workspace.model.WorkspaceRequest request =
        new io.boomerang.workspace.model.WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private static Workflow workflowWithWorkspace(String type, Map<String, Object> rawSpec) {
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setType(type);
    ws.setSpec(rawSpec);
    Workflow workflow = new Workflow();
    workflow.setName("workflow-with-workspace-" + type + "-" + System.nanoTime());
    workflow.setWorkspaces(new LinkedList<>(List.of(ws)));
    return workflow;
  }
}
