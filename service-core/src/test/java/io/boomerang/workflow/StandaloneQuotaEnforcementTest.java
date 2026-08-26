package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The standalone half of the engine-mode submit fix ({@link EngineModeWorkflowSubmitTest}): making
 * quotas a feature that is off in engine mode must not disable them in standalone, which is the
 * whole regression risk of that change. Standalone is the default mode here (no {@code flow.mode}
 * property), so {@code flow.quotas.enabled} defaults on - see
 * {@link io.boomerang.workspace.FlowQuotaProperties}.
 */
class StandaloneQuotaEnforcementTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  private static final String TASK_SLUG = "standalone-quota-test-task";

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
  void theWorkflowCountQuotaIsStillEnforcedInStandaloneMode() {
    String workspace = createWorkspace("standalone-quota-count", quotasWithWorkflowCount(0));
    workflowService.create(workspace, runnableWorkflow("quota-first-workflow", TASK_SLUG));

    setFeatureSetting(QUOTA_FEATURE, true);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, runnableWorkflow("quota-second-workflow", TASK_SLUG)));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  @Test
  void theRunDurationCeilingStillComesFromTheWorkspaceQuotaInStandaloneMode() {
    // The ceiling is gated on the quota subsystem (mode), not on the "workspaceQuotas" feature
    // setting - unchanged from before the fix, so this must hold with the feature off.
    String workspace = createWorkspace("standalone-quota-duration", quotasWithRunDuration(7));
    workflowService.create(
        workspace, runnableWorkflow("quota-duration-workflow", TASK_SLUG));

    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run =
        workflowService.submit(workspace, "quota-duration-workflow", request, false);

    assertEquals(7L, run.getTimeout());
  }

  private String createWorkspace(String name, Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private static Quotas quotasWithWorkflowCount(int count) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowCount(count);
    return quotas;
  }

  private static Quotas quotasWithRunDuration(int minutes) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunDuration(minutes);
    return quotas;
  }

}
