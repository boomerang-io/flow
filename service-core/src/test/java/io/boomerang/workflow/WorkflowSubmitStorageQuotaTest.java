package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The storage quota on a run submission. A submit request carries its own Workspaces, and their
 * spec is a raw {@code Object} - a {@link LinkedHashMap} once it has been over JSON - so the size
 * has to be converted before it can be read, and read as a Kubernetes quantity ("1Gi", "500Mi")
 * rather than a bare number. Both halves are the same rule the save-time check applies in
 * {@code setUpWorkspaceDefaults}: a size over the workspace's quota is refused with
 * QUOTA_EXCEEDED, and a spec that names no size is not a breach.
 */
class WorkflowSubmitStorageQuotaTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  private static final String TASK_SLUG = "submit-storage-quota-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @AfterEach
  void resetQuotaFeature() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void aWorkflowWorkspaceOverTheStorageQuotaIsRefused() {
    String workspace =
        createWorkspace("submit-storage-over-quota", quotasWithWorkflowStorage(2));
    String workflow = createWorkflow(workspace, "submit-storage-over-quota-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(
                workspace, workflow, submitWith("workflow", sizeSpec("5Gi")), false));

    assertEquals("QUOTA_EXCEEDED", refused.getReason());
    assertEquals("5Gi", refused.getArgs()[1]);
  }

  @Test
  void aWorkflowWorkspaceWithinTheStorageQuotaIsAccepted() {
    String workspace =
        createWorkspace("submit-storage-within-quota", quotasWithWorkflowStorage(10));
    String workflow = createWorkflow(workspace, "submit-storage-within-quota-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    WorkflowRun run =
        workflowService.submit(
            workspace, workflow, submitWith("workflow", sizeSpec("5Gi")), false);

    assertNotNull(run.getId());
  }

  @Test
  void aMegabyteQuantityIsConvertedToGiNotComparedAsText() {
    String workspace = createWorkspace("submit-storage-mi", quotasWithWorkflowStorage(2));
    String workflow = createWorkflow(workspace, "submit-storage-mi-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    // 3000Mi is 2.93Gi - over the 2Gi quota, though its number reads as 3000.
    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(
                workspace, workflow, submitWith("workflow", sizeSpec("3000Mi")), false));
    assertEquals("QUOTA_EXCEEDED", refused.getReason());

    // 1500Mi is 1.46Gi - within the same quota.
    WorkflowRun run =
        workflowService.submit(
            workspace, workflow, submitWith("workflow", sizeSpec("1500Mi")), false);
    assertNotNull(run.getId());
  }

  @Test
  void aWorkspaceSpecNamingNoSizeIsNotABreach() {
    String workspace = createWorkspace("submit-storage-no-size", quotasWithWorkflowStorage(2));
    String workflow = createWorkflow(workspace, "submit-storage-no-size-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    Map<String, Object> rawSpec = new LinkedHashMap<>();
    rawSpec.put("accessMode", "ReadWriteMany");

    WorkflowRun run =
        workflowService.submit(workspace, workflow, submitWith("workflow", rawSpec), false);

    assertNotNull(run.getId());
  }

  @Test
  void aWorkflowRunWorkspaceIsHeldToItsOwnStorageQuota() {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowStorage(25);
    quotas.setMaxWorkflowRunStorage(2);
    String workspace = createWorkspace("submit-storage-run-type", quotas);
    String workflow = createWorkflow(workspace, "submit-storage-run-type-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    // 5Gi clears the 25Gi workflow quota and breaches the 2Gi workflowrun one.
    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(
                workspace, workflow, submitWith("workflowrun", sizeSpec("5Gi")), false));

    assertEquals("QUOTA_EXCEEDED", refused.getReason());
    assertEquals("5Gi", refused.getArgs()[1]);
  }

  private String createWorkflow(String workspace, String name) {
    workflowService.create(workspace, runnableWorkflow(name, TASK_SLUG));
    return name;
  }

  private static WorkflowSubmitRequest submitWith(String type, Map<String, Object> rawSpec) {
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setName(type);
    ws.setType(type);
    ws.setSpec(rawSpec);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    request.setWorkspaces(new LinkedList<>(List.of(ws)));
    return request;
  }

  private static Map<String, Object> sizeSpec(String size) {
    Map<String, Object> rawSpec = new LinkedHashMap<>();
    rawSpec.put("size", size);
    return rawSpec;
  }

  private String createWorkspace(String name, Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private static Quotas quotasWithWorkflowStorage(int gi) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowStorage(gi);
    return quotas;
  }
}
