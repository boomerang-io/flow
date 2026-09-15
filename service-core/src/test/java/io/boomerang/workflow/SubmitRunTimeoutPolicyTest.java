package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * Submit is the one place a WorkflowRun's timeout is settled, for every path that creates a run:
 * the platform default when nobody declared one, a floor at the revision's critical path of task
 * budgets, and the workspace's run-duration quota as the ceiling. Before this, a run could be
 * created unguarded, or guarded more tightly than the tasks it had to wait for - the engine would
 * then reap healthy work at the run deadline.
 */
class SubmitRunTimeoutPolicyTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "run-timeout-policy-task";

  // The default this test seeds into the "workflowrun" settings document, deliberately different
  // from any quota ceiling below so the two cannot be confused for one another.
  private static final long PLATFORM_DEFAULT = 30L;

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private MessageSource messageSource;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    seedRunTimeoutSetting();
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
  }

  @Test
  void aRunWithNoDeclaredTimeoutGetsThePlatformDefault() {
    String workspace = createWorkspace("timeout-default", 120);
    workflowService.create(workspace, workflow("timeout-default-workflow", 0, 0));

    WorkflowRun run =
        workflowService.submit(workspace, "timeout-default-workflow", request(null), false);

    // The default, not the 120 minute ceiling the run used to be created at.
    assertEquals(PLATFORM_DEFAULT, run.getTimeout());
  }

  @Test
  void aTimeoutBelowTheCriticalPathOfTaskBudgetsIsRejected() {
    String workspace = createWorkspace("timeout-floor", 120);
    workflowService.create(workspace, workflow("timeout-floor-workflow", 20, 30));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workspace, "timeout-floor-workflow", request(10L), false));

    assertEquals("WORKFLOWRUN_TIMEOUT_TOO_SHORT", ex.getReason());
    String message = messageSource.getMessage(ex.getReason(), ex.getArgs(), Locale.ENGLISH);
    assertTrue(message.contains("50 minutes"), message);
    assertTrue(message.contains("10 minutes"), message);
  }

  @Test
  void aTimeoutAtTheCriticalPathIsAccepted() {
    String workspace = createWorkspace("timeout-floor-met", 120);
    workflowService.create(workspace, workflow("timeout-floor-met-workflow", 20, 30));

    WorkflowRun run =
        workflowService.submit(workspace, "timeout-floor-met-workflow", request(50L), false);

    assertEquals(50L, run.getTimeout());
  }

  @Test
  void theWorkspaceQuotaStillClampsTheTimeout() {
    String workspace = createWorkspace("timeout-ceiling", 7);
    workflowService.create(workspace, workflow("timeout-ceiling-workflow", 0, 0));

    WorkflowRun run =
        workflowService.submit(workspace, "timeout-ceiling-workflow", request(100L), false);

    assertEquals(7L, run.getTimeout());
  }

  @Test
  void aQuotaBelowTheCriticalPathIsRejectedNamingTheQuota() {
    String workspace = createWorkspace("timeout-ceiling-under-floor", 10);
    workflowService.create(workspace, workflow("timeout-ceiling-under-floor-workflow", 20, 30));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.submit(
                    workspace, "timeout-ceiling-under-floor-workflow", request(100L), false));

    assertEquals("WORKFLOWRUN_TIMEOUT_TOO_SHORT", ex.getReason());
    String message = messageSource.getMessage(ex.getReason(), ex.getArgs(), Locale.ENGLISH);
    assertTrue(message.contains("workspace maximum run duration"), message);
    assertTrue(message.contains("50 minutes"), message);
  }

  @Test
  void anInternalSubmitTakesTheSamePath() {
    // Schedules, webhooks and events all reach a run through internalSubmit, which skips the
    // relationship walk the public submit does - the policy has to sit below both.
    String workspace = createWorkspace("timeout-internal", 120);
    workflowService.create(workspace, workflow("timeout-internal-workflow", 20, 30));
    String workflowId = refOf(workspace, "timeout-internal-workflow");

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.internalSubmit(workspace, workflowId, request(10L), false));
    assertEquals("WORKFLOWRUN_TIMEOUT_TOO_SHORT", ex.getReason());

    WorkflowRun run =
        workflowService.internalSubmit(workspace, workflowId, request(null), false);
    assertEquals(PLATFORM_DEFAULT, run.getTimeout());
  }

  private String refOf(String workspace, String name) {
    return relationshipService
        .filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)),
            false)
        .get(0);
  }

  private static WorkflowSubmitRequest request(Long timeout) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    request.setTimeout(timeout);
    return request;
  }

  private String createWorkspace(String name, int maxRunDuration) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunDuration(maxRunDuration);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  // start -> first -> second -> end, so the critical path is firstTimeout + secondTimeout. A 0
  // leaves that task with no declared budget, contributing nothing to the floor.
  private static Workflow workflow(String name, long firstTimeout, long secondTimeout) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                task("start", TaskType.start, null, null, 0),
                task("first", TaskType.template, TASK_SLUG, "start", firstTimeout),
                task("second", TaskType.template, TASK_SLUG, "first", secondTimeout),
                task("end", TaskType.end, null, "second", 0))));
    return workflow;
  }

  private static WorkflowTask task(
      String name, TaskType type, String taskRef, String dependsOn, long timeout) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (timeout > 0) {
      task.setTimeout(timeout);
    }
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }

  // The platform default run timeout the loader seeds into the "workflowrun" settings document.
  // Merges rather than replaces - the shared Testcontainers Mongo means other classes may have
  // seeded their own keys into this one document.
  private void seedRunTimeoutSetting() {
    SettingEntity settings = settingsRepository.findOneByKey(RunTimeoutPolicy.WORKFLOWRUN_SETTINGS_KEY);
    if (settings == null) {
      settings = new SettingEntity();
      settings.setKey(RunTimeoutPolicy.WORKFLOWRUN_SETTINGS_KEY);
      settings.setName("WorkflowRun");
      settings.setConfig(new ArrayList<>());
    }
    List<SettingConfig> config = new ArrayList<>(settings.getConfig());
    config.stream()
        .filter(c -> RunTimeoutPolicy.DEFAULT_TIMEOUT.equals(c.getKey()))
        .findFirst()
        .ifPresentOrElse(
            c -> c.setValue(Long.toString(PLATFORM_DEFAULT)),
            () -> {
              SettingConfig added = new SettingConfig();
              added.setKey(RunTimeoutPolicy.DEFAULT_TIMEOUT);
              added.setType("number");
              added.setValue(Long.toString(PLATFORM_DEFAULT));
              config.add(added);
            });
    settings.setConfig(config);
    settingsRepository.save(settings);
  }
}
