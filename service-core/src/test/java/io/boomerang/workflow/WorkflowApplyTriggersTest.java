package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.schedule.ScheduleService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * PUT /workflow (WorkflowService.apply) treats a trigger the request leaves out as unchanged, as it
 * does labels: an update that only carries the definition must not switch the schedule trigger off
 * and disable the workflow's schedules.
 */
@TestPropertySource(properties = "flow.mode=engine")
class WorkflowApplyTriggersTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM_WORKSPACE = "system";
  private static final String TASK_SLUG = "apply-triggers-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private ScheduleService scheduleService;

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
  }

  @Test
  void anUpdateWithoutTriggersKeepsTheScheduleTriggerAndItsSchedules() {
    WorkflowSchedule schedule = createScheduledWorkflow("apply-no-triggers");

    Workflow update = runnableWorkflow("apply-no-triggers", TASK_SLUG);
    update.setTriggers(null);
    workflowService.apply(SYSTEM_WORKSPACE, update, false);

    assertTrue(stored("apply-no-triggers").getSchedule().getEnabled());
    assertEquals(
        WorkflowScheduleStatus.active, scheduleService.internalGet(schedule.getId()).getStatus());
  }

  @Test
  void anUpdateWithOneTriggerChangesOnlyThatTrigger() {
    createScheduledWorkflow("apply-one-trigger");

    Workflow update = runnableWorkflow("apply-one-trigger", TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setWebhook(new Trigger(true));
    update.setTriggers(triggers);
    workflowService.apply(SYSTEM_WORKSPACE, update, false);

    WorkflowTrigger stored = stored("apply-one-trigger");
    assertTrue(stored.getWebhook().getEnabled());
    assertTrue(stored.getSchedule().getEnabled());
    assertTrue(stored.getManual().getEnabled());
  }

  @Test
  void anUpdateThatTurnsTheScheduleTriggerOffStillDisablesTheSchedules() {
    WorkflowSchedule schedule = createScheduledWorkflow("apply-schedule-off");

    Workflow update = runnableWorkflow("apply-schedule-off", TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setSchedule(new Trigger(false));
    update.setTriggers(triggers);
    workflowService.apply(SYSTEM_WORKSPACE, update, false);

    assertFalse(stored("apply-schedule-off").getSchedule().getEnabled());
    assertEquals(
        WorkflowScheduleStatus.trigger_disabled,
        scheduleService.internalGet(schedule.getId()).getStatus());
  }

  @Test
  void aNewWorkflowWithoutTriggersGetsTheDefaults() {
    Workflow workflow = runnableWorkflow("apply-defaults", TASK_SLUG);
    workflow.setTriggers(null);
    workflowService.apply(SYSTEM_WORKSPACE, workflow, false);

    WorkflowTrigger stored = stored("apply-defaults");
    assertTrue(stored.getManual().getEnabled());
    assertFalse(stored.getSchedule().getEnabled());
    assertFalse(stored.getWebhook().getEnabled());
    assertFalse(stored.getEvent().getEnabled());
    assertFalse(stored.getGithub().getEnabled());
  }

  private WorkflowSchedule createScheduledWorkflow(String name) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    workflowService.create(SYSTEM_WORKSPACE, workflow);
    WorkflowSchedule request = new WorkflowSchedule();
    request.setName("hourly");
    request.setWorkflowRef(name);
    request.setType(WorkflowScheduleType.cron);
    request.setCronSchedule("0 * * * *");
    request.setTimezone("UTC");
    return scheduleService.create(SYSTEM_WORKSPACE, request);
  }

  private WorkflowTrigger stored(String name) {
    return workflowService.get(SYSTEM_WORKSPACE, name, Optional.empty(), false).getTriggers();
  }
}
