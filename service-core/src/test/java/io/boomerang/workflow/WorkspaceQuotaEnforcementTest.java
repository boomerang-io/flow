package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Workspace quotas are enforced by the stored {@code features.workspaceQuotas} setting alone, and
 * the run-duration ceiling comes from the workspace's own quota whatever that setting says.
 */
class WorkspaceQuotaEnforcementTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  private static final String TASK_SLUG = "standalone-quota-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private AuditEventRepository auditEventRepository;

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
  void theWorkflowCountQuotaIsEnforced() {
    String workspace = createWorkspace("standalone-quota-count", quotasWithWorkflowCount(1));
    workflowService.create(workspace, runnableWorkflow("quota-first-workflow", TASK_SLUG));
    workflowService.create(workspace, runnableWorkflow("quota-second-workflow", TASK_SLUG));

    setFeatureSetting(QUOTA_FEATURE, true);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, runnableWorkflow("quota-third-workflow", TASK_SLUG)));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  @Test
  void aWorkflowCountQuotaOfZeroIsUnlimited() {
    // Past the seeded platform default (10), so inheriting the default would refuse the last one.
    String workspace = createWorkspace("standalone-quota-unlimited", quotasWithWorkflowCount(0));
    setFeatureSetting(QUOTA_FEATURE, true);

    for (int i = 1; i <= 12; i++) {
      workflowService.create(workspace, runnableWorkflow("quota-unlimited-workflow-" + i, TASK_SLUG));
    }
  }

  @Test
  void theRunDurationCeilingComesFromTheWorkspaceQuota() {
    // The ceiling is not gated on the "workspaceQuotas" feature setting, so this holds with the
    // feature off.
    String workspace = createWorkspace("standalone-quota-duration", quotasWithRunDuration(7));
    workflowService.create(
        workspace, runnableWorkflow("quota-duration-workflow", TASK_SLUG));

    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run =
        workflowService.submit(workspace, "quota-duration-workflow", request, false);

    assertEquals(7L, run.getTimeout());
  }

  // A fired schedule names its workspace by the id the workflow's hasWorkflow edge yields, not by
  // name: under enforcement its run is admitted and held to the workspace's own quotas.
  @Test
  void aRunSubmittedUnderTheWorkspacesIdIsHeldToItsQuotas() {
    Quotas quotas = quotasWithRunDuration(7);
    quotas.setMaxWorkflowRunMonthly(1);
    String workspace = createWorkspace("quota-by-id", quotas);
    workflowService.create(workspace, runnableWorkflow("quota-by-id-workflow", TASK_SLUG));
    String workflowId =
        relationshipService
            .refsInWorkspace(
                RelationshipType.WORKFLOW, workspace, Optional.of(List.of("quota-by-id-workflow")))
            .get(0);
    String workspaceId =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowId);
    assertNotEquals(workspace, workspaceId, "the edge yields the workspace's id, not its name");

    setFeatureSetting(QUOTA_FEATURE, true);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run =
        workflowService.submit(workspaceId, "quota-by-id-workflow", request, false, "schedule-797");
    assertEquals(7L, run.getTimeout(), "the run-duration ceiling is the workspace's own");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.submit(
                    workspaceId, "quota-by-id-workflow", request, false, "schedule-797"));
    assertEquals("QUOTA_EXCEEDED", ex.getReason(), "the monthly quota is the workspace's own");
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

  // The count is checked before the create, so a workspace at its limit takes no more; one under
  // it still does.
  @Test
  void theWorkflowCountQuotaStopsAtTheLimit() {
    String workspace = createWorkspace("standalone-quota-count-edge", quotasWithWorkflowCount(2));
    workflowService.create(workspace, runnableWorkflow("quota-edge-first-workflow", TASK_SLUG));
    setFeatureSetting(QUOTA_FEATURE, true);

    workflowService.create(workspace, runnableWorkflow("quota-edge-second-workflow", TASK_SLUG));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    workspace, runnableWorkflow("quota-edge-third-workflow", TASK_SLUG)));
    assertEquals("QUOTA_EXCEEDED", ex.getReason());
  }

  // With quotas on, a run is queued un-started so the quota recheck can discard it; once admitted
  // it is released like a started run: a run with a workspace takes its deadline from its creation
  // and waits ready for the dispatcher, so the timeout sweep can end it when no dispatcher claims.
  @Test
  void aRunAdmittedUnderQuotasTakesItsDeadlineWhileItWaitsForADispatcher() {
    String workspace = createWorkspace("standalone-quota-deadline", quotasWithRunDuration(5));
    Workflow workflow = runnableWorkflow("quota-deadline-workflow", TASK_SLUG);
    WorkflowWorkspace runWorkspace = new WorkflowWorkspace();
    runWorkspace.setSize("1Gi");
    workflow.setWorkspace(runWorkspace);
    workflowService.create(workspace, workflow);
    setFeatureSetting(QUOTA_FEATURE, true);

    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(workspace, "quota-deadline-workflow", request, true);

    assertEquals(RunStatus.ready, run.getStatus(), "a run with a workspace waits for the dispatcher");
    assertNotNull(
        workflowRunRepository.findById(run.getId()).orElseThrow().getTimeoutAt(),
        "the deadline runs from submit while no dispatcher claims, quotas on or off");
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
