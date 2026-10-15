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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The storage quota on a run submission. A submit request may carry its own workspace, whose
 * size is a Kubernetes quantity ("1Gi", "500Mi") rather than a bare number. Both halves are the
 * same rule the save-time check applies in {@code applyWorkspaceDefaults}: a size over the
 * owning workspace's storage quota is refused with QUOTA_EXCEEDED, and a workspace that names
 * no size is not a breach.
 */
class WorkflowSubmitStorageQuotaTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  private static final String TASK_SLUG = "submit-storage-quota-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
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
  void aWorkspaceOverTheStorageQuotaIsRefused() {
    String workspace = createWorkspace("submit-storage-over-quota", quotasWithStorage(2));
    String workflow = createWorkflow(workspace, "submit-storage-over-quota-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workspace, workflow, submitWith("5Gi"), false));

    assertEquals("QUOTA_EXCEEDED", refused.getReason());
    assertEquals("5Gi", refused.getArgs()[1]);
  }

  @Test
  void aWorkspaceWithinTheStorageQuotaIsAccepted() {
    String workspace = createWorkspace("submit-storage-within-quota", quotasWithStorage(10));
    String workflow = createWorkflow(workspace, "submit-storage-within-quota-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    WorkflowRun run = workflowService.submit(workspace, workflow, submitWith("5Gi"), false);

    assertNotNull(run.getId());
    assertEquals("5Gi", run.getWorkspace().getSize(), "the request's workspace overrides");
  }

  @Test
  void aMegabyteQuantityIsConvertedToGiNotComparedAsText() {
    String workspace = createWorkspace("submit-storage-mi", quotasWithStorage(2));
    String workflow = createWorkflow(workspace, "submit-storage-mi-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    // 3000Mi is 2.93Gi - over the 2Gi quota, though its number reads as 3000.
    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workspace, workflow, submitWith("3000Mi"), false));
    assertEquals("QUOTA_EXCEEDED", refused.getReason());

    // 1500Mi is 1.46Gi - within the same quota.
    WorkflowRun run = workflowService.submit(workspace, workflow, submitWith("1500Mi"), false);
    assertNotNull(run.getId());
  }

  @Test
  void aWorkspaceNamingNoSizeIsNotABreach() {
    String workspace = createWorkspace("submit-storage-no-size", quotasWithStorage(2));
    String workflow = createWorkflow(workspace, "submit-storage-no-size-workflow");
    setFeatureSetting(QUOTA_FEATURE, true);

    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setAccessMode("ReadWriteMany");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    request.setWorkspace(ws);

    WorkflowRun run = workflowService.submit(workspace, workflow, request, false);

    assertNotNull(run.getId());
  }

  private String createWorkflow(String workspace, String name) {
    workflowService.create(workspace, runnableWorkflow(name, TASK_SLUG));
    return name;
  }

  private static WorkflowSubmitRequest submitWith(String size) {
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setSize(size);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    request.setWorkspace(ws);
    return request;
  }

  private String createWorkspace(String name, Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private static Quotas quotasWithStorage(int gi) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkspaceStorage(gi);
    return quotas;
  }
}
