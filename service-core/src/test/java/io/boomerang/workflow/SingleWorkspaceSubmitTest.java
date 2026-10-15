package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.schedule.ScheduleService;
import io.boomerang.schedule.ScheduleWatcher;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * An embedded engine ({@code flow.workspace.single=true}, security off) serves the seeded {@code
 * system} workspace alone, and POST /api/v2/workspace/{workspace}/workflow/{name}/submit is the
 * only HTTP route that starts a WorkflowRun - so the whole run lifecycle has to work against it:
 * create, submit, delete, and a due schedule firing.
 *
 * <p>The system workspace is seeded with unlimited quotas, so a run nobody declared a timeout for
 * takes that unlimited ceiling as its budget (RunTimeoutPolicy), and a requested timeout beneath it
 * is honoured.
 */
@TestPropertySource(properties = {"flow.workspace.single=true", "flow.security.enabled=false"})
class SingleWorkspaceSubmitTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM_WORKSPACE = "system";

  private static final String TASK_SLUG = "single-submit-test-task";

  // The seeded system workspace's maxWorkflowRunDuration: unlimited.
  private static final long UNLIMITED_DURATION = Integer.MAX_VALUE;

  @Autowired private WorkflowService workflowService;
  @Autowired private ScheduleService scheduleService;
  @Autowired private ScheduleWatcher scheduleWatcher;
  @Autowired private WorkflowScheduleRepository scheduleRepository;

  @BeforeEach
  void seedFixtures() {
    // ParamLayerService reads both parameter feature flags on every submit.
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
  }

  @Test
  void submittingAWorkflowRunSucceeds() {
    createWorkflow("single-submit-succeeds");

    WorkflowRun run = submit("single-submit-succeeds");

    assertNotNull(run);
    assertNotNull(run.getId());
    // Admitted (queued but not started) - not `invalid`, which is what a rejected DAG leaves.
    assertEquals(RunStatus.ready, run.getStatus());
  }

  @Test
  void theRunDurationCeilingIsTheSystemWorkspaceQuota() {
    createWorkflow("single-submit-timeout-default");

    WorkflowRun run = submit("single-submit-timeout-default");

    assertEquals(UNLIMITED_DURATION, run.getTimeout());
  }

  @Test
  void aRequestedTimeoutBelowTheCeilingIsHonoured() {
    createWorkflow("single-submit-timeout-request");

    WorkflowSubmitRequest request = newSubmitRequest();
    request.setTimeout(5L);
    WorkflowRun run =
        workflowService.submit(
            SYSTEM_WORKSPACE, "single-submit-timeout-request", request, false);

    assertEquals(5L, run.getTimeout());
  }

  @Test
  void deletingAWorkflowSucceeds() {
    createWorkflow("single-delete-no-schedules");

    workflowService.delete(SYSTEM_WORKSPACE, "single-delete-no-schedules");

    assertTrue(
        workflowService
            .query(
                SYSTEM_WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(List.of("single-delete-no-schedules")))
            .getContent()
            .isEmpty());
  }

  @Test
  void aDueScheduleStartsARun() {
    Workflow workflow = runnableWorkflow("single-schedule-fires", TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    workflowService.create(SYSTEM_WORKSPACE, workflow);
    WorkflowSchedule request = new WorkflowSchedule();
    request.setName("hourly");
    request.setWorkflowRef("single-schedule-fires");
    request.setType(WorkflowScheduleType.cron);
    request.setCronSchedule("0 * * * *");
    request.setTimezone("UTC");
    WorkflowSchedule schedule = scheduleService.create(SYSTEM_WORKSPACE, request);
    WorkflowScheduleEntity due = scheduleRepository.findById(schedule.getId()).orElseThrow();
    due.setNextFireAt(new Date(System.currentTimeMillis() - 60_000));
    scheduleRepository.save(due);

    scheduleWatcher.fireDueSchedules();

    assertTrue(
        workflowRunRepository.findAll().stream()
            .anyMatch(
                run ->
                    TriggerEnum.schedule.getTrigger().equals(run.getTrigger())
                        && schedule.getId().equals(run.getInitiatedByRef())),
        "the fired schedule starts a run");
  }

  private void createWorkflow(String name) {
    workflowService.create(SYSTEM_WORKSPACE, runnableWorkflow(name, TASK_SLUG));
  }

  private WorkflowRun submit(String name) {
    return workflowService.submit(SYSTEM_WORKSPACE, name, newSubmitRequest(), false);
  }

  private static WorkflowSubmitRequest newSubmitRequest() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }
}
