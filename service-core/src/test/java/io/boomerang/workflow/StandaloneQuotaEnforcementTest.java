package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
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
  @Autowired private AuditEventRepository auditEventRepository;

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

  @Test
  void theMonthlyRunQuotaSurvivesWorkflowDeletion() {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunMonthly(1);
    String workspace = createWorkspace("quota-monthly-audit", quotas);
    workflowService.create(workspace, runnableWorkflow("quota-audit-first-workflow", TASK_SLUG));
    setFeatureSetting(QUOTA_FEATURE, true);
    seedAuditCapture();
    try {
      WorkflowSubmitRequest request = new WorkflowSubmitRequest();
      request.setTrigger(TriggerEnum.manual);
      WorkflowRun run =
          workflowService.submit(workspace, "quota-audit-first-workflow", request, false);

      // The run-creation audit event is written asynchronously; the quota only counts it once
      // it has landed, so wait for it before removing the live evidence.
      awaitEngine("run CREATE audit event")
          .untilAsserted(
              () ->
                  org.junit.jupiter.api.Assertions.assertTrue(
                      auditEventRepository.findAll().stream()
                          .anyMatch(
                              event ->
                                  run.getId().equals(event.getResourceId())
                                      && "CREATE".equals(event.getAction()))));

      // Deleting the Workflow removes its relationship node, so the live count no longer sees
      // the run - only the audit event still testifies to it.
      workflowService.delete(workspace, "quota-audit-first-workflow");
      workflowService.create(
          workspace, runnableWorkflow("quota-audit-second-workflow", TASK_SLUG));

      BoomerangException refused =
          assertThrows(
              BoomerangException.class,
              () ->
                  workflowService.submit(
                      workspace, "quota-audit-second-workflow", request, false));
      assertEquals("QUOTA_EXCEEDED", refused.getReason());
    } finally {
      removeAuditCapture();
    }
  }

  private void seedAuditCapture() {
    if (settingsRepository.findOneByKey("audit") != null) {
      return;
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey("audit");
    settings.setName("Audit");
    settings.setConfig(List.of(auditConfig("enabled", "true"), auditConfig("level", "WRITE")));
    settingsRepository.save(settings);
  }

  /** Leaves the shared database as the other test classes expect it: capture off. */
  private void removeAuditCapture() {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  private static SettingConfig auditConfig(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
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
