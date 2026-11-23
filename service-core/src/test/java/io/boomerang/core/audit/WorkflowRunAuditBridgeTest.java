package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.common.model.WorkflowTemplateRef;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The engine's WorkflowRun transitions land in the audit trail: the first status change records a
 * CREATE event owned by the run's Workspace, completion records an UPDATE event carrying the
 * terminal status and duration and the run's cumulative duration, and an unresolvable owner still
 * records the event (with no workspace) rather than failing the transition. A run of an unmetered
 * workflow, or of a link to one, is marked on both events and records no cumulative duration.
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

  /*
   * A run with nothing to execute is completed invalid when it is queued. That end goes through
   * the completion Compare-And-Set like any other, so it is audited and counted.
   */
  @Test
  void aRunInvalidWhenQueuedRecordsItsEnd() {
    String workspace = createWorkspace("run-audit-invalid-ws");
    Workflow empty = new Workflow();
    empty.setName("run-audit-invalid-workflow");
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency afterStart = new WorkflowTaskDependency();
    afterStart.setTaskRef("start");
    end.setDependencies(new LinkedList<>(List.of(afterStart)));
    empty.setTasks(new LinkedList<>(List.of(start, end)));
    workflowService.create(workspace, empty);

    WorkflowRun run =
        workflowService.submit(workspace, "run-audit-invalid-workflow", manualRequest(), false);

    assertThat(run.getStatus()).isEqualTo(RunStatus.invalid);
    awaitEngine("invalid run audit event")
        .untilAsserted(
            () -> {
              List<AuditEventEntity> events = eventsFor(run.getId(), "UPDATE");
              assertThat(events).hasSize(1);
              assertThat(events.get(0).getPayload())
                  .containsEntry("status", "invalid")
                  .containsEntry("phase", "completed");
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

  @Test
  void theCompletionCarriesTheCumulativeDurationOfTheDispatchedTasks() {
    String workspace = createWorkspace("run-audit-cumulative-ws");
    String workflowRef = createWorkflow(workspace, "run-audit-cumulative-workflow");
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
    taskRun(run, TaskType.template, RunPhase.completed, 61_000L);
    taskRun(run, TaskType.script, RunPhase.completed, 30_000L);
    taskRun(run, TaskType.approval, RunPhase.completed, 600_000L);
    TaskRunEntity parent = taskRun(run, TaskType.template, RunPhase.completed, 500_000L);
    WorkflowTaskForeach foreach = new WorkflowTaskForeach();
    foreach.setItems(List.of("a", "b"));
    parent.setForeach(foreach);
    taskRunRepository.save(parent);

    complete(run, RunStatus.succeeded);

    awaitEngine("run UPDATE with its cumulative duration")
        .untilAsserted(
            () ->
                assertThat(onlyEvent(run.getId(), "UPDATE").getPayload())
                    .containsEntry("cumulativeDuration", 91_000L)
                    .doesNotContainKey("unmetered"));
  }

  // A cancel can complete a run while a task is still running: it counts up to the event.
  @Test
  void aCancelWithATaskRunningCountsItUpToTheEvent() {
    String workspace = createWorkspace("run-audit-cancel-running-ws");
    String workflowRef = createWorkflow(workspace, "run-audit-cancel-running-workflow");
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
    TaskRunEntity running = taskRun(run, TaskType.template, RunPhase.running, 0L);
    long started = System.currentTimeMillis() - 45_000L;
    running.setStartTime(new Date(started));
    taskRunRepository.save(running);

    complete(run, RunStatus.cancelled);
    long counted = System.currentTimeMillis() - started;

    awaitEngine("cancelled run UPDATE")
        .untilAsserted(
            () -> {
              Object recorded =
                  onlyEvent(run.getId(), "UPDATE").getPayload().get("cumulativeDuration");
              assertThat(((Number) recorded).longValue()).isBetween(45_000L, counted);
            });
  }

  @Test
  void aRunOfAnUnmeteredSystemWorkflowIsMarkedAndCarriesNoCumulativeDuration() {
    String name = "run-audit-unmetered-" + System.nanoTime();
    String workflowRef = createWorkflow("system", name);
    String previous = setSettingValue("quotas", "unmetered.workflows", "another, " + name);
    try {
      WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.notstarted, RunPhase.pending);
      taskRun(run, TaskType.template, RunPhase.completed, 61_000L);

      create(run);
      complete(run, RunStatus.succeeded);

      awaitEngine("unmetered run events")
          .untilAsserted(
              () -> {
                assertThat(onlyEvent(run.getId(), "CREATE").getPayload())
                    .containsEntry("unmetered", true);
                assertThat(onlyEvent(run.getId(), "UPDATE").getPayload())
                    .containsEntry("unmetered", true)
                    .doesNotContainKey("cumulativeDuration");
              });
    } finally {
      setSettingValue("quotas", "unmetered.workflows", previous);
    }
  }

  @Test
  void aRunOfALinkToAnUnmeteredTemplateIsMarked() {
    Workflow template = runnableWorkflow("run-audit-unmetered-tpl-" + System.nanoTime(), TASK_SLUG);
    template.setType(WorkflowType.template);
    template = workflowService.create("system", template);
    String workspace = createWorkspace("run-audit-link-ws");
    Workflow link = new Workflow();
    link.setName(template.getName());
    WorkflowTemplateRef ref = new WorkflowTemplateRef();
    ref.setName(template.getName());
    link.setTemplateRef(ref);
    workflowService.create(workspace, link);
    String linkRef = workflowRef(workspace, template.getName());
    String previous = setSettingValue("quotas", "unmetered.workflows", template.getName());
    try {
      WorkflowRunEntity run = savedWorkflowRun(linkRef, RunStatus.notstarted, RunPhase.pending);

      create(run);
      complete(run, RunStatus.succeeded);

      awaitEngine("link run events")
          .untilAsserted(
              () -> {
                assertThat(onlyEvent(run.getId(), "CREATE").getPayload())
                    .containsEntry("unmetered", true);
                assertThat(onlyEvent(run.getId(), "UPDATE").getPayload())
                    .containsEntry("unmetered", true)
                    .doesNotContainKey("cumulativeDuration");
              });
    } finally {
      setSettingValue("quotas", "unmetered.workflows", previous);
    }
  }

  // The list names workflows of the system workspace: a workflow of another workspace with the
  // same name is metered.
  @Test
  void aSameNamedWorkflowOutsideTheSystemWorkspaceStaysMetered() {
    String name = "run-audit-same-name-" + System.nanoTime();
    createWorkflow("system", name);
    String workspace = createWorkspace("run-audit-same-name-ws");
    String workflowRef = createWorkflow(workspace, name);
    String previous = setSettingValue("quotas", "unmetered.workflows", name);
    try {
      WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.notstarted, RunPhase.pending);
      taskRun(run, TaskType.template, RunPhase.completed, 1_000L);

      create(run);
      complete(run, RunStatus.succeeded);

      awaitEngine("metered run events")
          .untilAsserted(
              () -> {
                // The minutes are the completion's alone: a CREATE carries none.
                assertThat(onlyEvent(run.getId(), "CREATE").getPayload())
                    .doesNotContainKey("unmetered")
                    .doesNotContainKey("cumulativeDuration");
                assertThat(onlyEvent(run.getId(), "UPDATE").getPayload())
                    .doesNotContainKey("unmetered")
                    .containsEntry("cumulativeDuration", 1_000L);
              });
    } finally {
      setSettingValue("quotas", "unmetered.workflows", previous);
    }
  }

  @Test
  void withCaptureOffNothingIsWritten() {
    String workspace = createWorkspace("run-audit-capture-off-ws");
    String workflowRef = createWorkflow(workspace, "run-audit-capture-off-workflow");
    removeAuditSettings();
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.notstarted, RunPhase.pending);
    taskRun(run, TaskType.template, RunPhase.completed, 61_000L);

    create(run);
    complete(run, RunStatus.succeeded);

    Awaitility.await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        auditEventRepository.findAll().stream()
                            .filter(event -> run.getId().equals(event.getResourceId())))
                    .isEmpty());
  }

  private TaskRunEntity taskRun(WorkflowRunEntity run, TaskType type, RunPhase phase, long duration) {
    TaskRunEntity taskRun =
        savedTaskRun(
            "task-" + System.nanoTime(),
            type,
            RunPhase.completed.equals(phase) ? RunStatus.succeeded : RunStatus.running,
            phase,
            run.getWorkflowRef(),
            run.getId());
    taskRun.setStartTime(new Date(System.currentTimeMillis() - duration));
    taskRun.setDuration(duration);
    return taskRunRepository.save(taskRun);
  }

  private void create(WorkflowRunEntity run) {
    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            run.getId(),
            run.getWorkflowRef(),
            RunStatus.notstarted,
            RunPhase.pending,
            RunStatus.running,
            RunPhase.running));
  }

  private void complete(WorkflowRunEntity run, RunStatus status) {
    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            run.getId(), run.getWorkflowRef(), RunStatus.running, RunPhase.running, status, RunPhase.completed));
  }

  private AuditEventEntity onlyEvent(String resourceId, String action) {
    List<AuditEventEntity> events = eventsFor(resourceId, action);
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  private String createWorkflow(String workspace, String name) {
    workflowService.create(workspace, runnableWorkflow(name, TASK_SLUG));
    return workflowRef(workspace, name);
  }

  private String workflowRef(String workspace, String name) {
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)))
        .get(0);
  }

  private List<AuditEventEntity> eventsFor(String resourceId, String action) {
    return auditEventRepository.findAll().stream()
        .filter(
            event -> resourceId.equals(event.getResourceId()) && action.equals(event.getAction()))
        .toList();
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name + "-" + System.nanoTime());
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
