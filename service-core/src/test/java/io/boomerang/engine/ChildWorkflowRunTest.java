package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.ExecutionCondition;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.model.WorkflowRunTransition;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Child workflow composition end to end: the lineage a runworkflow task stamps on its child, the
 * wait that parks the parent task until the child finishes, the cascade that cancels the children
 * of a cancelled run, and the nesting cap.
 */
class ChildWorkflowRunTest extends AbstractEngineIntegrationTest {

  // The cap this class runs under: a root run may still start one child (depth 0), and that
  // child's own runworkflow task is the first to trip it - one lineage hop, not five.
  private static final String NESTING_CAP = "1";

  private static final String WORKSPACE = "child-workflow-workspace";

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private ChildWorkflowRunListener childWorkflowRunListener;

  private String runWorkflowTaskRef;
  private String echoTaskRef;

  @BeforeEach
  void seedCatalogueAndWorkspace() {
    seedRelationshipRoot();
    seedNestingCap();
    if (relationshipService
        .filter(RelationshipType.WORKSPACE, Optional.of(List.of(WORKSPACE)))
        .isEmpty()) {
      relationshipService.createNode(
          RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, Optional.empty());
    }
    Task runWorkflow = new Task();
    runWorkflow.setName("child-run-workflow-" + System.nanoTime());
    runWorkflow.setType(TaskType.runworkflow);
    runWorkflow
        .getSpec()
        .setParams(List.of(param("workflowRef", "text"), param("wait", "boolean")));
    runWorkflowTaskRef = taskService.create(runWorkflow).getId();

    Task echo = new Task();
    echo.setName("child-echo-" + System.nanoTime());
    echo.setType(TaskType.template);
    echo.getSpec().setImage("busybox:latest");
    echo.getSpec().setCommand(List.of("echo"));
    echoTaskRef = taskService.create(echo).getId();
  }

  @Test
  void fireAndForgetSucceedsAtOnceAndStampsTheLineage() {
    String childWorkflowId = createLeafWorkflow("child-fire-and-forget");
    String parentWorkflowId = createParentWorkflow("parent-fire-and-forget", childWorkflowId, false);

    String parentRunId = submit(parentWorkflowId);

    awaitEngine("fire-and-forget runworkflow task succeeded")
        .untilAsserted(
            () -> {
              TaskRunEntity task = parentTask(parentRunId);
              assertNotNull(task);
              assertEquals(RunStatus.succeeded, task.getStatus());
              assertEquals(RunPhase.completed, task.getPhase());
            });

    TaskRunEntity task = parentTask(parentRunId);
    String childRunId = resultValue(task, "workflowRunRef");
    assertNotNull(childRunId);
    WorkflowRunEntity childRun = workflowRunRepository.findById(childRunId).orElseThrow();
    // Lineage is the typed pair, not a new field: trigger "task" plus the submitting TaskRun's id.
    assertEquals("task", childRun.getTrigger());
    assertEquals(task.getId(), childRun.getInitiatedByRef());
  }

  @Test
  void waitParksTheParentAndSucceedsItWhenTheChildSucceeds() {
    String childWorkflowId = createLeafWorkflow("child-wait-succeeds");
    String parentWorkflowId = createParentWorkflow("parent-wait-succeeds", childWorkflowId, true);

    String parentRunId = submit(parentWorkflowId);
    String childRunId = awaitParkedChild(parentRunId);
    // A child wait carries no wake time, so the watcher's time-based resume never picks it up.
    assertNull(parentTask(parentRunId).getWaitUntil());

    endLeafRun(childRunId, RunStatus.succeeded);

    awaitEngine("waiting parent task succeeded with its child")
        .untilAsserted(
            () -> {
              TaskRunEntity task = parentTask(parentRunId);
              assertEquals(RunStatus.succeeded, task.getStatus());
              assertEquals(RunPhase.completed, task.getPhase());
            });
    awaitEngine("parent run completed")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(parentRunId).orElseThrow();
              assertEquals(RunStatus.succeeded, run.getStatus());
              assertEquals(RunPhase.completed, run.getPhase());
            });
  }

  @Test
  void aFailedChildFailsTheParentTaskWithChildRunFailed() {
    String childWorkflowId = createLeafWorkflow("child-wait-fails");
    String parentWorkflowId = createParentWorkflow("parent-wait-fails", childWorkflowId, true);

    String parentRunId = submit(parentWorkflowId);
    String childRunId = awaitParkedChild(parentRunId);

    endLeafRun(childRunId, RunStatus.failed);

    awaitEngine("waiting parent task failed with its child")
        .untilAsserted(
            () -> {
              TaskRunEntity task = parentTask(parentRunId);
              assertEquals(RunStatus.failed, task.getStatus());
              assertEquals(RunPhase.completed, task.getPhase());
              assertEquals("ChildRunFailed", task.getStatusReason());
              assertTrue(task.getStatusMessage().contains(childRunId));
            });
  }

  @Test
  void aRedeliveredChildCompletionEndsTheParentOnce() {
    String childWorkflowId = createLeafWorkflow("child-idempotent");
    String parentWorkflowId = createParentWorkflow("parent-idempotent", childWorkflowId, true);

    String parentRunId = submit(parentWorkflowId);
    String childRunId = awaitParkedChild(parentRunId);
    endLeafRun(childRunId, RunStatus.failed);
    awaitEngine("waiting parent task failed with its child")
        .untilAsserted(
            () -> assertEquals(RunStatus.failed, parentTask(parentRunId).getStatus()));

    TaskRunEntity ended = parentTask(parentRunId);
    // Re-delivering the child's completion transition must not re-end an already terminal task.
    childWorkflowRunListener.onWorkflowRunTransition(
        new WorkflowRunTransition(
            childRunId,
            childWorkflowId,
            RunStatus.running,
            RunPhase.running,
            RunStatus.failed,
            RunPhase.completed));

    TaskRunEntity afterReplay = parentTask(parentRunId);
    assertEquals(RunStatus.failed, afterReplay.getStatus());
    assertEquals("ChildRunFailed", afterReplay.getStatusReason());
    assertEquals(ended.getDuration(), afterReplay.getDuration());
  }

  @Test
  void cancellingTheParentCancelsItsInFlightChild() {
    String childWorkflowId = createLeafWorkflow("child-cascade-cancel");
    String parentWorkflowId = createParentWorkflow("parent-cascade-cancel", childWorkflowId, true);

    String parentRunId = submit(parentWorkflowId);
    String childRunId = awaitParkedChild(parentRunId);

    workflowRunService.cancel(parentRunId);

    awaitEngine("child run cancelled with its parent")
        .untilAsserted(
            () -> {
              WorkflowRunEntity child = workflowRunRepository.findById(childRunId).orElseThrow();
              assertEquals(RunStatus.cancelled, child.getStatus());
              assertEquals(RunPhase.completed, child.getPhase());
            });
  }

  @Test
  void theNestingCapFailsTheTaskAndNoGrandchildIsCreated() {
    String leafWorkflowId = createLeafWorkflow("nesting-leaf");
    // Middle runs a leaf; top runs middle. With the cap at 1 the top run's task is at depth 0 and
    // submits, and the middle run's task is at depth 1 and must not.
    String middleWorkflowId = createParentWorkflow("nesting-middle", leafWorkflowId, false);
    String topWorkflowId = createParentWorkflow("nesting-top", middleWorkflowId, false);

    String topRunId = submit(topWorkflowId);

    awaitEngine("middle run created")
        .untilAsserted(() -> assertNotNull(resultValue(parentTask(topRunId), "workflowRunRef")));
    String middleRunId = resultValue(parentTask(topRunId), "workflowRunRef");

    awaitEngine("middle run's runworkflow task failed on the nesting cap")
        .untilAsserted(
            () -> {
              TaskRunEntity task = parentTask(middleRunId);
              assertNotNull(task);
              assertEquals(RunStatus.failed, task.getStatus());
              assertEquals("NestingDepthExceeded", task.getStatusReason());
            });
    assertNull(resultValue(parentTask(middleRunId), "workflowRunRef"));
    assertTrue(
        workflowRunRepository
            .findByWorkflowRefAndPhaseIn(
                leafWorkflowId,
                List.of(RunPhase.pending, RunPhase.queued, RunPhase.running, RunPhase.completed))
            .isEmpty());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String submit(String workflowId) {
    return workflowService.submit(workflowId, new WorkflowSubmitRequest(), true).getId();
  }

  /** The single runworkflow TaskRun of a run. */
  private TaskRunEntity parentTask(String workflowRunId) {
    return taskRunRepository.findByWorkflowRunRef(workflowRunId).stream()
        .filter(t -> TaskType.runworkflow.equals(t.getType()))
        .findFirst()
        .orElse(null);
  }

  private String awaitParkedChild(String parentRunId) {
    awaitEngine("runworkflow task parked as waiting")
        .untilAsserted(
            () -> {
              TaskRunEntity task = parentTask(parentRunId);
              assertNotNull(task);
              assertEquals(RunStatus.waiting, task.getStatus());
              assertEquals(RunPhase.running, task.getPhase());
              assertNotNull(resultValue(task, "workflowRunRef"));
            });
    return resultValue(parentTask(parentRunId), "workflowRunRef");
  }

  /** Drive a leaf run's only template task to the given terminal status, as a dispatcher would. */
  private void endLeafRun(String leafRunId, RunStatus status) {
    awaitEngine("leaf template TaskRun ready")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("work", leafRunId)
                        .isPresent()));
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", leafRunId).orElseThrow().getId();
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(status);
    taskRunService.end(taskRunId, Optional.of(endRequest));
  }

  private static String resultValue(TaskRunEntity taskRun, String name) {
    if (taskRun == null || taskRun.getResults() == null) {
      return null;
    }
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .findFirst()
        .orElse(null);
  }

  /**
   * start -> work (template, parks awaiting a dispatcher) -> end, with the end node depending on
   * work SUCCEEDING. The default {@code always} condition would complete the run successfully even
   * when its only task failed, so a failing child could never be observed.
   */
  private String createLeafWorkflow(String name) {
    return createWorkflow(
        name, node("work", TaskType.template, echoTaskRef, "start", null), ExecutionCondition.success);
  }

  /** start -> child (runworkflow against childWorkflowId) -> end. */
  private String createParentWorkflow(String name, String childWorkflowId, boolean wait) {
    List<RunParam> params =
        new LinkedList<>(
            List.of(
                new RunParam("workflowRef", childWorkflowId),
                new RunParam("wait", Boolean.toString(wait))));
    return createWorkflow(
        name,
        node("child", TaskType.runworkflow, runWorkflowTaskRef, "start", params),
        ExecutionCondition.always);
  }

  /*
   * Created unscoped (as the other engine tests do) then anchored under a workspace node: the
   * engine's ChildWorkflowRunCreated listener resolves a child run's owning workspace by walking
   * the Workflow's hasWorkflow edge, and a workflow with no such edge cannot own a run.
   */
  private String createWorkflow(
      String name, WorkflowTask middle, ExecutionCondition endCondition) {
    WorkflowTask end = node("end", TaskType.end, null, middle.getName(), null);
    end.getDependencies().get(0).setExecutionCondition(endCondition);
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setTasks(
        new LinkedList<>(List.of(node("start", TaskType.start, null, null, null), middle, end)));
    Workflow created = workflowService.create(workflow, false).getBody();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        WORKSPACE,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        created.getId(),
        created.getName(),
        Optional.empty(),
        Optional.empty());
    return created.getId();
  }

  private static WorkflowTask node(
      String name, TaskType type, String taskRef, String dependsOn, List<RunParam> params) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (params != null) {
      task.setParams(params);
    }
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }

  private static AbstractParam param(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }

  /** The "workflowrun" settings document the loader seeds, with only the key this class reads. */
  private void seedNestingCap() {
    SettingEntity settings =
        settingsRepository.findOneByKey(TaskExecutionService.WORKFLOWRUN_SETTINGS_KEY);
    if (settings != null) {
      return;
    }
    settings = new SettingEntity();
    settings.setKey(TaskExecutionService.WORKFLOWRUN_SETTINGS_KEY);
    settings.setName("Workspace Configuration - Activity Storage");
    SettingConfig config = new SettingConfig();
    config.setKey(TaskExecutionService.MAX_NESTING_DEPTH);
    config.setType("number");
    config.setValue(NESTING_CAP);
    settings.setConfig(List.of(config));
    settingsRepository.save(settings);
  }
}
