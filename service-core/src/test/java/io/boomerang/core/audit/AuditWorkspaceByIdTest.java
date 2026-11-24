package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.workflow.QuotaService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.WorkspaceWorkflowControllerV2;
import io.boomerang.workspace.WorkspaceControllerV2;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The audit trail keys a workspace's events on its id, with its name at the time beside it: a
 * renamed workspace keeps its monthly runs, minutes, run totals and audit listing; a workspace
 * created under a deleted one's name starts with none of the old history; the listing filters by
 * id or name; and every writer, the routes, the explicit emitter and the run bridge, records both.
 */
class AuditWorkspaceByIdTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "audit-workspace-by-id-task";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private WorkspaceControllerV2 workspaceController;
  @Autowired private WorkspaceWorkflowControllerV2 workflowController;
  @Autowired private WorkflowService workflowService;
  @Autowired private QuotaService quotaService;
  @Autowired private AuditControllerV2 auditController;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  @BeforeEach
  void captureOn() {
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
  }

  @AfterEach
  void captureOff() {
    RequestContextHolder.resetRequestAttributes();
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  @Test
  void aRenamedWorkspaceKeepsItsMonthAndItsListing() {
    String name = workspace("audit-by-id-rename");
    String id = idOf(name);
    String runId = completedRun(name, 61_000L, 120_000L);
    awaitEvents(runId, 2);

    WorkspaceRequest rename = new WorkspaceRequest();
    rename.setName(name + "-renamed");
    String renamed = workspaceService.patch(name, rename).getName();

    CurrentQuotas quotas = quotaService.current(renamed);
    assertThat(quotas.getCurrentRuns()).isEqualTo(1);
    assertThat(quotas.getCurrentCumulativeDuration()).as("61 s is 2 minutes").isEqualTo(2L);
    assertThat(quotas.getCurrentRunTotalDuration()).isEqualTo(120_000L);
    assertThat(quotas.getCurrentRunAverageDuration()).isEqualTo(120_000L);
    for (String asked : List.of(renamed, id)) {
      assertThat(listed(asked))
          .as("listed by " + asked)
          .filteredOn(event -> runId.equals(event.resourceId()))
          .hasSize(2)
          .allSatisfy(
              event -> {
                assertThat(event.workspaceId()).isEqualTo(id);
                assertThat(event.workspaceName()).as("its name at the time").isEqualTo(name);
              });
    }
  }

  @Test
  void aWorkspaceCreatedUnderADeletedOnesNameStartsFresh() {
    String name = workspace("audit-by-id-reused");
    String oldId = idOf(name);
    String runId = completedRun(name, 61_000L, 120_000L);
    awaitEvents(runId, 2);
    workspaceService.delete(name);

    String recreated = workspace(name);
    CurrentQuotas quotas = quotaService.current(recreated);

    assertThat(idOf(recreated)).isNotEqualTo(oldId);
    assertThat(quotas.getCurrentRuns()).isZero();
    assertThat(quotas.getCurrentCumulativeDuration()).isZero();
    assertThat(quotas.getCurrentRunTotalDuration()).isZero();
    assertThat(listed(recreated)).noneMatch(event -> runId.equals(event.resourceId()));
    assertThat(listed(oldId))
        .as("the deleted workspace's history is still under its id")
        .anyMatch(event -> runId.equals(event.resourceId()));
  }

  @Test
  void theRoutesAndTheEmitterRecordTheIdAndTheNameAtTheTime() {
    String name = workspace("audit-by-id-writers");
    String id = idOf(name);

    // A route naming the workspace by its path, a rename among them: the name before the call.
    WorkspaceRequest rename = new WorkspaceRequest();
    rename.setName(name + "-moved");
    workspaceController.updateWorkspace(name, rename);
    String moved = name + "-moved";
    // The explicit emitter, as a dropped schedule fire records its refused submit.
    String workflowRef =
        relationshipService
            .refsInWorkspace(RelationshipType.WORKFLOW, moved, Optional.of(List.of("writer-workflow")))
            .get(0);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.schedule);
    workflowService.recordRefusedSubmit(
        moved, workflowRef, request, "audit-by-id-schedule", "Refused in a test", new IllegalStateException("test"));
    // The workspace's own delete: resolved before the workspace is gone.
    workspaceController.deleteWorkflow(moved);

    awaitEngine("the writers' events")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events =
                  auditEventRepository.findAll().stream().filter(e -> id.equals(e.getWorkspaceId())).toList();
              assertThat(events)
                  .anySatisfy(
                      e -> {
                        assertThat(e.getResourceType()).isEqualTo("workspace");
                        assertThat(e.getAction()).isEqualTo("UPDATE");
                        assertThat(e.getWorkspaceName()).isEqualTo(name);
                      })
                  .anySatisfy(
                      e -> {
                        assertThat(e.getAction()).isEqualTo("SUBMIT");
                        assertThat(e.getWorkspaceName()).isEqualTo(moved);
                      })
                  .anySatisfy(
                      e -> {
                        assertThat(e.getResourceType()).isEqualTo("workspace");
                        assertThat(e.getAction()).isEqualTo("DELETE");
                        assertThat(e.getWorkspaceName()).isEqualTo(moved);
                      });
            });
  }

  // The route resolves the workspace once, and the access check that follows reads it from the
  // request's memo: capturing the submit costs no graph read of its own. A submit of a workflow
  // the workspace does not have is refused after that check, before any run would be audited.
  @Test
  void aCapturedSubmitReadsTheGraphNoMoreThanAnUncapturedOne() {
    String name = workspace("audit-by-id-submit");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);

    DatabaseCommands.Recorded<Object> captured = submitInRequest(name, request);
    captureOff();
    DatabaseCommands.Recorded<Object> uncaptured = submitInRequest(name, request);

    assertThat(onNodes(captured)).isEqualTo(onNodes(uncaptured));
  }

  private DatabaseCommands.Recorded<Object> submitInRequest(String workspace, WorkflowSubmitRequest request) {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      return DatabaseCommands.record(
          () -> {
            try {
              return workflowController.submitWorkflow(workspace, "no-such-workflow", request);
            } catch (RuntimeException refused) {
              return refused;
            }
          });
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static long onNodes(DatabaseCommands.Recorded<Object> recorded) {
    return recorded.names().stream().filter(name -> name.endsWith("rel_nodes")).count();
  }

  // A completed run of the workspace's workflow, through the run bridge: a task of the given
  // duration and a run of the given duration.
  private String completedRun(String workspace, long taskDuration, long runDuration) {
    String workflowRef =
        relationshipService
            .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of("writer-workflow")))
            .get(0);
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.notstarted, RunPhase.pending);
    run.setDuration(runDuration);
    workflowRunRepository.save(run);
    TaskRunEntity task =
        savedTaskRun("work", TaskType.template, RunStatus.succeeded, RunPhase.completed, workflowRef, run.getId());
    task.setStartTime(new Date(System.currentTimeMillis() - taskDuration));
    task.setDuration(taskDuration);
    taskRunRepository.save(task);
    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            run.getId(), workflowRef, RunStatus.notstarted, RunPhase.pending, RunStatus.running, RunPhase.running));
    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            run.getId(), workflowRef, RunStatus.running, RunPhase.running, RunStatus.succeeded, RunPhase.completed));
    return run.getId();
  }

  private void awaitEvents(String runId, int count) {
    awaitEngine("the run's events")
        .until(() -> auditEventRepository.findAll().stream().filter(e -> runId.equals(e.getResourceId())).count() == count);
  }

  private List<AuditEvent> listed(String workspace) {
    return auditController
        .query(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(List.of(workspace)),
            Optional.empty(),
            Optional.empty(),
            0,
            100,
            Direction.DESC)
        .getContent();
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(new Quotas());
    String created = workspaceService.create(request).getName();
    workflowService.create(created, runnableWorkflow("writer-workflow", TASK_SLUG));
    return created;
  }

  private String idOf(String name) {
    return workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getId();
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
