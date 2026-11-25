package io.boomerang.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.QuotaService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A schedule's fire that is dropped after its last attempt leaves a run on the Workflow's Activity:
 * cancelled, with the reason {@code SubmitRefused} and what refused it, the schedule as its
 * initiator. The entry is written whatever refused the fire - the month's quota included - and
 * uses none of the month's runs itself.
 */
class DroppedScheduleFireTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";
  private static final String TASK_SLUG = "dropped-fire-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private QuotaService quotaService;
  @Autowired private ScheduleWatcher scheduleWatcher;
  @Autowired private WorkflowScheduleRepository scheduleRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  private SettingEntity auditSettingsAdded;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      auditSettingsAdded = settingsRepository.save(settings);
    }
  }

  @AfterEach
  void restoreSharedState() {
    setFeatureSetting(QUOTA_FEATURE, false);
    if (auditSettingsAdded != null) {
      settingsRepository.delete(auditSettingsAdded);
      auditSettingsAdded = null;
    }
  }

  @Test
  void aFireDroppedWithTheMonthSpentLeavesACancelledEntryThatUsesNoneOfTheMonth() {
    String workspace = workspace("dropped-fire-month", 1);
    String workflowRef = workflow(workspace, "dropped-fire-month-workflow", true);
    // The month's one run is spent before the schedule fires.
    WorkflowSubmitRequest manual = new WorkflowSubmitRequest();
    manual.setTrigger(TriggerEnum.manual);
    WorkflowRun spent =
        workflowService.submit(workspace, "dropped-fire-month-workflow", manual, false);
    setFeatureSetting(QUOTA_FEATURE, true);
    WorkflowScheduleEntity schedule = recurring(workflowRef, "nightly");

    fireUntilDropped(schedule.getId());

    List<WorkflowRunEntity> entries = entries(workflowRef);
    assertThat(entries).hasSize(1);
    WorkflowRunEntity entry = entries.get(0);
    assertThat(entry.getStatus()).isEqualTo(RunStatus.cancelled);
    assertThat(entry.getPhase()).isEqualTo(RunPhase.completed);
    assertThat(entry.getStatusReason()).isEqualTo(StatusReason.SUBMIT_REFUSED);
    assertThat(entry.getStatusMessage())
        .matches(
            "Schedule \"nightly\" fired at \\d\\d:00 UTC and no run started after 3 attempts\\."
                + " QUOTA_EXCEEDED: maximum runs this month");
    assertThat(entry.getTrigger()).isEqualTo("schedule");
    assertThat(entry.getInitiatedByRef()).isEqualTo(schedule.getId());
    assertThat(entry.getDuration()).isZero();
    assertThat(entry.getTimeoutAt()).isNull();
    assertThat(taskRunRepository.findByWorkflowRunRef(entry.getId())).isEmpty();
    // Owned by the workspace like any run: its own route reads it.
    assertThat(workflowRunService.get(workspace, entry.getId(), false).getBody().getId())
        .isEqualTo(entry.getId());
    // It holds no slot and is not a run of the month.
    assertThat(quotaService.current(workspace).getCurrentRuns()).isEqualTo(1);
    assertThat(quotaService.current(workspace).getCurrentQueuedRuns()).isZero();
    assertThat(quotaService.current(workspace).getCurrentConcurrentRuns()).isEqualTo(1);
    assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
        .isEqualTo(WorkflowScheduleStatus.active);
    awaitEngine("the dropped fire's audit event")
        .untilAsserted(
            () ->
                assertThat(refusedSubmits(workspace))
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.getResourceId()).isEqualTo("dropped-fire-month-workflow");
                          assertThat(event.getPayload().get("errorSummary"))
                              .isEqualTo("QUOTA_EXCEEDED: maximum runs this month");
                          assertThat(event.getPayload().get("detail"))
                              .asInstanceOf(InstanceOfAssertFactories.MAP)
                              .containsEntry("trigger", "schedule")
                              .containsEntry("workflowRun", entry.getId())
                              .containsEntry("initiatedBy", schedule.getId());
                        }));
    // Never audited as created, so the audit side of the monthly count leaves it out too.
    assertThat(
            auditEventRepository.findAll().stream()
                .filter(event -> entry.getId().equals(event.getResourceId()))
                .toList())
        .isEmpty();
    workflowRunService.cancel(spent.getId());
  }

  @Test
  void aDroppedOneOffScheduleMovesToErrorAndLeavesAnEntry() {
    String workspace = workspace("dropped-fire-once", 0);
    // The schedule trigger is off, which refuses every fire.
    String workflowRef = workflow(workspace, "dropped-fire-once-workflow", false);
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setWorkflowRef(workflowRef);
    schedule.setName("one-off");
    schedule.setType(WorkflowScheduleType.runOnce);
    schedule.setStatus(WorkflowScheduleStatus.active);
    schedule.setDateSchedule(Date.from(Instant.parse("2026-03-04T02:00:00Z")));
    schedule.setTimezone("UTC");
    schedule.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
    schedule = scheduleRepository.save(schedule);

    fireUntilDropped(schedule.getId());

    assertThat(scheduleRepository.findById(schedule.getId()).orElseThrow().getStatus())
        .isEqualTo(WorkflowScheduleStatus.error);
    assertThat(entries(workflowRef))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getStatus()).isEqualTo(RunStatus.cancelled);
              assertThat(entry.getStatusReason()).isEqualTo(StatusReason.SUBMIT_REFUSED);
              assertThat(entry.getStatusMessage())
                  .isEqualTo(
                      "Schedule \"one-off\" fired at 02:00 UTC and no run started after 3"
                          + " attempts. WORKFLOWRUN_TRIGGER_DISABLED: trigger disabled");
            });
  }

  // A workflow with nothing between start and end refuses every fire before a run exists, so the
  // fire is dropped and its entry carries that refusal.
  @Test
  void aFireOfAWorkflowWithNothingToRunLeavesAnEntryCarryingTheRefusal() {
    String workspace = workspace("dropped-fire-not-runnable", 0);
    String workflowRef = startAndEndWorkflow(workspace, "dropped-fire-not-runnable-workflow");
    WorkflowScheduleEntity schedule = recurring(workflowRef, "nightly");

    fireUntilDropped(schedule.getId());

    assertThat(workflowRunRepository.findByWorkflowRefAndPhaseIn(workflowRef, List.of(RunPhase.values())))
        .as("the entry is the only run")
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getStatus()).isEqualTo(RunStatus.cancelled);
              assertThat(entry.getPhase()).isEqualTo(RunPhase.completed);
              assertThat(entry.getStatusReason()).isEqualTo(StatusReason.SUBMIT_REFUSED);
              assertThat(entry.getStatusMessage())
                  .matches(
                      "Schedule \"nightly\" fired at \\d\\d:00 UTC and no run started after 3"
                          + " attempts\\. WORKFLOW_NOT_RUNNABLE: no tasks between start and end");
              assertThat(entry.getTrigger()).isEqualTo("schedule");
              assertThat(entry.getInitiatedByRef()).isEqualTo(schedule.getId());
              assertThat(taskRunRepository.findByWorkflowRunRef(entry.getId())).isEmpty();
            });
  }

  // Retrying the entry runs what the fire asked for. The retry is a real run: it carries no
  // SubmitRefused reason, so it is a run of the month, where the entry it came from is not.
  @Test
  void aRetryOfADroppedFireEntryIsARunOfTheMonthWithoutTheEntrysReason() {
    String workspace = workspace("dropped-fire-retry", 1);
    String workflowRef = workflow(workspace, "dropped-fire-retry-workflow", true);
    WorkflowSubmitRequest manual = new WorkflowSubmitRequest();
    manual.setTrigger(TriggerEnum.manual);
    WorkflowRun spent =
        workflowService.submit(workspace, "dropped-fire-retry-workflow", manual, false);
    setFeatureSetting(QUOTA_FEATURE, true);
    WorkflowScheduleEntity schedule = recurring(workflowRef, "nightly");
    fireUntilDropped(schedule.getId());
    WorkflowRunEntity entry = entries(workflowRef).get(0);
    assertThat(quotaService.current(workspace).getCurrentRuns()).isEqualTo(1);
    // Room for the retry: the month's limit is raised from 1 to 2.
    Quotas raised = new Quotas();
    raised.setMaxWorkflowRunMonthly(2);
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setQuotas(raised);
    workspaceService.patch(workspace, patch);

    WorkflowRun retried = workflowRunService.retry(workspace, entry.getId()).getBody();

    // The retry starts its run, as a submit does.
    awaitEngine("the retry to start")
        .untilAsserted(
            () ->
                assertThat(workflowRunRepository.findById(retried.getId()).orElseThrow().getStatus())
                    .isEqualTo(RunStatus.running));
    WorkflowRunEntity clone = workflowRunRepository.findById(retried.getId()).orElseThrow();
    assertThat(clone.getStatusReason()).isNull();
    assertThat(clone.getStatusMessage()).isNull();
    assertThat(clone.getTrigger()).isEqualTo("retry");
    assertThat(clone.getInitiatedByRef()).isEqualTo(entry.getId());
    assertThat(entries(workflowRef)).as("the entry alone carries the reason").hasSize(1);
    assertThat(quotaService.current(workspace).getCurrentRuns())
        .as("the spent run and the retry; the entry is not a run")
        .isEqualTo(2);
    workflowRunService.cancel(retried.getId());
    workflowRunService.cancel(spent.getId());
  }

  // The fire and its two retries; the schedule watcher's own background sweep may take one.
  private void fireUntilDropped(String scheduleId) {
    for (int attempt = 0; attempt < 6 && droppedEntries(scheduleId) == 0; attempt++) {
      WorkflowScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElseThrow();
      if (schedule.getNextFireAt() != null) {
        schedule.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
        scheduleRepository.save(schedule);
      }
      scheduleWatcher.fireDueSchedules();
    }
    // The fire sets the scheduler's identity on this thread; the assertions read as the test.
    establishTestIdentity();
  }

  private long droppedEntries(String scheduleId) {
    return workflowRunRepository.findAll().stream()
        .filter(run -> scheduleId.equals(run.getInitiatedByRef()))
        .count();
  }

  private List<WorkflowRunEntity> entries(String workflowRef) {
    return workflowRunRepository
        .findByWorkflowRefAndPhaseIn(workflowRef, List.of(RunPhase.values()))
        .stream()
        .filter(run -> StatusReason.SUBMIT_REFUSED.equals(run.getStatusReason()))
        .toList();
  }

  private List<AuditEventEntity> refusedSubmits(String workspace) {
    return auditEventRepository.findAll().stream()
        .filter(
            event ->
                workspace.equals(event.getWorkspaceName())
                    && "SUBMIT".equals(event.getAction())
                    && "FAILED".equals(event.getOutcome())
                    && "workflow".equals(event.getResourceType()))
        .toList();
  }

  private WorkflowScheduleEntity recurring(String workflowRef, String name) {
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setWorkflowRef(workflowRef);
    schedule.setName(name);
    schedule.setType(WorkflowScheduleType.cron);
    schedule.setStatus(WorkflowScheduleStatus.active);
    schedule.setCronSchedule("0 * * * *");
    schedule.setTimezone("UTC");
    schedule.setNextFireAt(new Date(System.currentTimeMillis() - 1000));
    return scheduleRepository.save(schedule);
  }

  private String workspace(String name, int monthlyRuns) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunMonthly(monthlyRuns);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private String workflow(String workspace, String name, boolean scheduleTrigger) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setSchedule(new Trigger(scheduleTrigger));
    workflow.setTriggers(triggers);
    workflowService.create(workspace, workflow);
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)))
        .get(0);
  }

  private String startAndEndWorkflow(String workspace, String name) {
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency afterStart = new WorkflowTaskDependency();
    afterStart.setTaskRef("start");
    end.setDependencies(new LinkedList<>(List.of(afterStart)));
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(new LinkedList<>(List.of(start, end)));
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    workflowService.create(workspace, workflow);
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)))
        .get(0);
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
