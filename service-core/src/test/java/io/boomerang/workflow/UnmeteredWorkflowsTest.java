package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * A workflow of the system workspace the {@code unmetered.workflows} setting lists, and every
 * workspace's link to it, uses none of a workspace's monthly quotas: its runs are accepted with the
 * month's runs and minutes spent, and neither the audit count nor the live count of the month holds
 * them. A metered run is refused only on the true count.
 */
class UnmeteredWorkflowsTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";
  private static final String TASK_SLUG = "unmetered-workflows-task";
  private static final String METERED = "unmetered-test-metered";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private QuotaService quotaService;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private MongoTemplate mongoTemplate;

  private final List<String> runs = new ArrayList<>();
  private String previousList;
  private String template;
  private String systemWorkflow;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      settingsRepository.save(settings);
    }
    Workflow definition = runnableWorkflow("unmetered-test-template-" + System.nanoTime(), TASK_SLUG);
    definition.setType(WorkflowType.template);
    template = workflowService.create(SYSTEM, definition).getName();
    systemWorkflow =
        workflowService
            .create(SYSTEM, runnableWorkflow("unmetered-test-system-" + System.nanoTime(), TASK_SLUG))
            .getName();
    previousList =
        setSettingValue("quotas", "unmetered.workflows", "someone-else, " + template + "," + systemWorkflow);
  }

  @AfterEach
  void restore() {
    setFeatureSetting("workspaceQuotas", false);
    setSettingValue("quotas", "unmetered.workflows", previousList);
    runs.forEach(workflowRunService::cancel);
    runs.clear();
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  @Test
  void aLinkToAnUnmeteredTemplateInTwoWorkspacesSkipsBothMonthlyChecksAndBothCounts() {
    String first = spentWorkspace("unmetered-first");
    String second = spentWorkspace("unmetered-second");

    for (String workspace : List.of(first, second)) {
      submit(workspace, template);
      submit(workspace, template);
      assertThatThrownBy(() -> submit(workspace, METERED))
          .isInstanceOfSatisfying(
              BoomerangException.class,
              spent -> assertThat(spent.getArgs()[0]).isEqualTo(QuotaService.MONTHLY_RUNS));
    }

    for (String workspace : List.of(first, second)) {
      awaitEngine("the link runs' CREATE events are marked")
          .until(() -> unmeteredCreates(workspace) == 2);
      assertThat(quotaService.current(workspace).getCurrentRuns())
          .as("only the metered run is a run of the month")
          .isEqualTo(1);
      assertThat(quotaService.current(workspace).getCurrentCumulativeDuration()).isEqualTo(1L);
    }
  }

  @Test
  void aMeteredRunIsRefusedOnlyOnTheTrueCount() {
    String workspace = workspace("unmetered-recount", 2, 0);
    submit(workspace, template);
    submit(workspace, template);

    // The live count holds the two link runs: recounted without them, the month has room for two.
    submit(workspace, METERED);
    submit(workspace, METERED);
    assertThatThrownBy(() -> submit(workspace, METERED))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            spent ->
                assertThat(spent.getArgs()).containsExactly(QuotaService.MONTHLY_RUNS, 2L, 2));
    assertThat(quotaService.current(workspace).getCurrentRuns()).isEqualTo(2);
  }

  @Test
  void anUnmeteredSystemWorkflowSkipsBothMonthlyChecksAndBothCounts() {
    WorkspaceEntity system = workspaceRepository.findByNameIgnoreCase(SYSTEM).orElseThrow();
    Quotas stored = system.getQuotas();
    Quotas spent = new Quotas();
    QuotaService.overlay(spent, stored);
    spent.setMaxWorkflowRunMonthly(1);
    spent.setMaxCumulativeDuration(1);
    spent.setMaxConcurrentRuns(0);
    system.setQuotas(spent);
    workspaceRepository.save(system);
    try {
      String metered =
          workflowService
              .create(SYSTEM, runnableWorkflow(METERED + "-" + System.nanoTime(), TASK_SLUG))
              .getName();
      minutesUsed(SYSTEM, 1);
      submit(SYSTEM, metered);
      String spentBy = runs.get(runs.size() - 1);
      awaitEngine("the metered run's CREATE event")
          .until(
              () ->
                  auditEventRepository.findAll().stream()
                      .anyMatch(e -> spentBy.equals(e.getResourceId()) && "CREATE".equals(e.getAction())));
      setFeatureSetting("workspaceQuotas", true);
      int before = quotaService.current(SYSTEM).getCurrentRuns();
      assertThat(before).as("the month is spent").isGreaterThanOrEqualTo(1);

      submit(SYSTEM, systemWorkflow);
      submit(SYSTEM, systemWorkflow);

      awaitEngine("the system workflow's CREATE events are marked")
          .until(() -> unmeteredCreates(SYSTEM, systemWorkflow) == 2);
      assertThat(quotaService.current(SYSTEM).getCurrentRuns())
          .as("neither side of the month's count holds them")
          .isEqualTo(before);
    } finally {
      WorkspaceEntity restored = workspaceRepository.findByNameIgnoreCase(SYSTEM).orElseThrow();
      restored.setQuotas(stored);
      workspaceRepository.save(restored);
    }
  }

  // The Workflows page reads each workflow's flag off the list: a link to a listed template and a
  // listed template shown beside the workspace's own are unmetered; the workspace's own workflow of
  // a listed system name, and a template not listed, are not.
  @Test
  void theWorkflowListMarksEachWorkflowByItsOwnWorkspace() {
    Workflow shown = runnableWorkflow("unmetered-test-shown-" + System.nanoTime(), TASK_SLUG);
    shown.setType(WorkflowType.template);
    String shownTemplate = workflowService.create(SYSTEM, shown).getName();
    Workflow unlisted = runnableWorkflow("unmetered-test-unlisted-" + System.nanoTime(), TASK_SLUG);
    unlisted.setType(WorkflowType.template);
    String unlistedTemplate = workflowService.create(SYSTEM, unlisted).getName();
    setSettingValue(
        "quotas", "unmetered.workflows", template + ", " + systemWorkflow + ", " + shownTemplate);
    String workspace = workspace("unmetered-list", 0, 0);
    workflowService.create(workspace, runnableWorkflow(systemWorkflow, TASK_SLUG));
    submit(workspace, template);

    java.util.Map<String, Boolean> listed = flags(workspace);

    assertThat(listed)
        .containsEntry(template, true)
        .containsEntry(shownTemplate, true)
        .containsEntry(systemWorkflow, false)
        .containsEntry(unlistedTemplate, false)
        .containsEntry(METERED, false);
    assertThat(flags(SYSTEM))
        .containsEntry(systemWorkflow, true)
        .containsEntry(template, true)
        .containsEntry(unlistedTemplate, false);
  }

  private java.util.Map<String, Boolean> flags(String workspace) {
    java.util.Map<String, Boolean> flags = new java.util.HashMap<>();
    workflowService
        .query(
            workspace,
            java.util.Optional.empty(),
            java.util.Optional.of(0),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty())
        .getContent()
        .forEach(workflow -> flags.put(workflow.getName(), workflow.getUnmetered()));
    return flags;
  }

  // A workspace whose month's runs and minutes are both spent by a metered workflow of its own.
  private String spentWorkspace(String name) {
    String workspace = workspace(name, 1, 1);
    minutesUsed(workspace, 1);
    setFeatureSetting("workspaceQuotas", false);
    submit(workspace, METERED);
    setFeatureSetting("workspaceQuotas", true);
    return workspace;
  }

  private String workspace(String name, int maxMonthly, int maxCumulative) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(0);
    quotas.setMaxWorkflowRunMonthly(maxMonthly);
    quotas.setMaxCumulativeDuration(maxCumulative);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name + "-" + System.nanoTime());
    request.setDisplayName(request.getName());
    request.setQuotas(quotas);
    String workspace = workspaceService.create(request).getName();
    workflowService.create(workspace, runnableWorkflow(METERED, TASK_SLUG));
    setFeatureSetting("workspaceQuotas", true);
    return workspace;
  }

  private void submit(String workspace, String workflow) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    runs.add(workflowService.submit(workspace, workflow, request, false).getId());
  }

  private long unmeteredCreates(String workspace) {
    return unmeteredCreates(workspace, template);
  }

  private long unmeteredCreates(String workspace, String workflowName) {
    return auditEventRepository.findAll().stream()
        .filter(event -> workspace.equals(event.getWorkspaceName()))
        .filter(event -> "CREATE".equals(event.getAction()))
        .filter(event -> workflowName.equals(event.getResourceName()))
        .filter(event -> Boolean.TRUE.equals(event.getPayload().get("unmetered")))
        .count();
  }

  private void minutesUsed(String workspace, long minutes) {
    AuditEventEntity event = new AuditEventEntity();
    // The trail keys a workspace's events on its id.
    event.setWorkspaceId(workspaceService.get(workspace).getId());
    event.setWorkspaceName(workspace);
    event.setAction("UPDATE");
    event.setResourceType("workflowrun");
    event.setResourceId("unmetered-test-run-" + System.nanoTime());
    event.setTime(new Date());
    event.getPayload().put("cumulativeDuration", minutes * 60_000L);
    mongoTemplate.insert(event);
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
