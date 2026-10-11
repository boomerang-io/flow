package io.boomerang.workspace;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.WorkflowService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Insights roll up the audit trail's workflow-run events, which the engine writes in both modes,
 * so a run submitted against the engine-mode {@code system} workspace appears in its insights.
 */
@TestPropertySource(properties = "flow.mode=engine")
class EngineModeInsightsTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM_WORKSPACE = "system";
  private static final String TASK_SLUG = "engine-insights-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private InsightsService insightsService;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    if (!relationshipService.doesSlugOrRefExistForType(
        RelationshipType.WORKSPACE, SYSTEM_WORKSPACE)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          SYSTEM_WORKSPACE,
          SYSTEM_WORKSPACE,
          Optional.empty(),
          Optional.empty());
    }
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      settingsRepository.save(settings);
    }
  }

  /** Leaves the shared database as the other test classes expect it: capture off. */
  @AfterEach
  void removeAuditSettings() {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  @Test
  void aSubmittedRunAppearsInTheSystemWorkspaceInsights() {
    Date from = new Date(System.currentTimeMillis() - 60_000);
    workflowService.create(SYSTEM_WORKSPACE, runnableWorkflow("engine-insights", TASK_SLUG));
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    workflowService.submit(SYSTEM_WORKSPACE, "engine-insights", request, false);

    awaitEngine("the run's audit event reaches the insights roll-up")
        .until(
            () ->
                insightsService
                        .get(
                            SYSTEM_WORKSPACE,
                            from,
                            new Date(System.currentTimeMillis() + 60_000),
                            Optional.empty(),
                            Optional.empty())
                        .getTotalRuns()
                    >= 1);
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
