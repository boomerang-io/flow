package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.model.WorkflowRunTransition;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The engine's WorkflowRun transitions land in the audit trail: the first status change records a
 * CREATE event owned by the run's Workspace, completion records an UPDATE event carrying the
 * terminal status and duration, and an unresolvable owner still records the event (with no
 * workspace) rather than failing the transition.
 */
class WorkflowRunAuditBridgeTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "run-audit-bridge-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting("workspaceQuotas", false);
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
  void anAdmittedRunRecordsACreateEventOwnedByItsWorkspace() {
    String workspace = createWorkspace("run-audit-create-ws");
    workflowService.create(workspace, runnableWorkflow("run-audit-create-workflow", TASK_SLUG));

    WorkflowRun run =
        workflowService.submit(workspace, "run-audit-create-workflow", manualRequest(), false);

    awaitEngine("run CREATE audit event")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events = eventsFor(run.getId(), "CREATE");
              assertThat(events).hasSize(1);
              AuditEventEntity event = events.get(0);
              assertThat(event.getResourceType()).isEqualTo("workflowrun");
              assertThat(event.getWorkspaceId()).isEqualTo(workspace);
              assertThat(event.getResourceName()).isEqualTo("run-audit-create-workflow");
              assertThat(event.getActorId()).isEqualTo("integration-test-principal");
              assertThat(event.getOutcome()).isEqualTo("SUCCESS");
              assertThat(event.getPayload())
                  .containsEntry("workflowRef", run.getWorkflowRef())
                  .containsEntry("workflowName", "run-audit-create-workflow")
                  .containsEntry("status", "ready");
            });
  }

  @Test
  void aCompletedRunRecordsAnUpdateEventCarryingTheTerminalStatus() {
    String workspace = createWorkspace("run-audit-terminal-ws");
    workflowService.create(workspace, runnableWorkflow("run-audit-terminal-workflow", TASK_SLUG));
    WorkflowRun run =
        workflowService.submit(workspace, "run-audit-terminal-workflow", manualRequest(), false);

    workflowRunService.cancel(run.getId());

    awaitEngine("run terminal audit event")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events = eventsFor(run.getId(), "UPDATE");
              assertThat(events).hasSize(1);
              AuditEventEntity event = events.get(0);
              assertThat(event.getResourceType()).isEqualTo("workflowrun");
              assertThat(event.getWorkspaceId()).isEqualTo(workspace);
              assertThat(event.getPayload())
                  .containsEntry("status", "cancelled")
                  .containsEntry("phase", "completed")
                  .containsKey("duration");
            });
  }

  @Test
  void anUnresolvableWorkspaceStillRecordsTheEventWithoutOne() {
    String runId = "run-audit-orphan-" + System.nanoTime();

    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            runId,
            "run-audit-missing-workflow-ref",
            RunStatus.notstarted,
            RunPhase.pending,
            RunStatus.ready,
            RunPhase.pending));

    awaitEngine("orphan run audit event")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events = eventsFor(runId, "CREATE");
              assertThat(events).hasSize(1);
              assertThat(events.get(0).getWorkspaceId()).isNull();
              assertThat(events.get(0).getPayload())
                  .containsEntry("workflowRef", "run-audit-missing-workflow-ref");
            });
  }

  private List<AuditEventEntity> eventsFor(String resourceId, String action) {
    return auditEventRepository.findAll().stream()
        .filter(
            event -> resourceId.equals(event.getResourceId()) && action.equals(event.getAction()))
        .toList();
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(new Quotas());
    return workspaceService.create(request).getName();
  }

  private static WorkflowSubmitRequest manualRequest() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
