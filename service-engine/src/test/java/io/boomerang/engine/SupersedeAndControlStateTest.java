package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TimeoutCause;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Generation mechanics (supersede + re-run from a step) and the control-state fields that replaced
 * the boomerang.io/* orchestration annotations. Supersede is driven end to end; the control-state
 * migration is asserted on the retry and timeout paths directly.
 */
class SupersedeAndControlStateTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private TaskExecutionService taskExecutionService;

  @Test
  void reRunFromStepSupersedesDownstreamClosureAndReDrives() {
    String workflowId = createLinearWorkflow("supersede-flow");
    String wfRunId = runToCompletion(workflowId);

    // A downstream node that was skipped must still be part of the superseded closure.
    TaskRunEntity b = liveByName("b", wfRunId);
    b.setStatus(RunStatus.skipped);
    taskRunRepository.save(b);

    String aOldId = liveByName("a", wfRunId).getId();
    String bOldId = b.getId();
    String endOldId = liveByName("end", wfRunId).getId();
    String startId = liveByName("start", wfRunId).getId();

    workflowRunService.retryFromTask(wfRunId, aOldId);

    // The step and its downstream closure (skipped node included) are retired at attempt 1.
    for (String oldId : List.of(aOldId, bOldId, endOldId)) {
      TaskRunEntity retired = taskRunRepository.findById(oldId).orElseThrow();
      assertNotNull(retired.getSuperseded(), oldId);
      assertNotNull(retired.getSuperseded().getAt(), oldId);
      assertEquals(1, retired.getAttempt(), oldId);
    }
    // Upstream is untouched - still the live generation.
    assertNull(taskRunRepository.findById(startId).orElseThrow().getSuperseded());

    // Exactly one live generation per node; history keeps both generations of the retired nodes.
    for (String name : List.of("a", "b", "end")) {
      assertEquals(
          1, taskRunRepository.findByWorkflowRunRefAndSupersededAtIsNull(wfRunId).stream()
              .filter(t -> name.equals(t.getName())).count(), name);
      assertEquals(
          2, taskRunRepository.findByNameAndWorkflowRunRef(name, wfRunId).size(), name);
    }

    // The fresh generation copies the pinned spec and re-drives (start re-queues it).
    TaskRunEntity freshA = liveByName("a", wfRunId);
    assertNull(freshA.getSuperseded());
    assertNull(freshA.getAttempt());
    assertEquals("busybox:latest", freshA.getSpec().getImage());
    assertEquals(RunPhase.running, workflowRunRepository.findById(wfRunId).orElseThrow().getPhase());
    awaitEngine("the re-run to re-queue the step")
        .untilAsserted(
            () -> assertEquals(RunStatus.ready, liveByName("a", wfRunId).getStatus()));

    // Idempotent: re-superseding the already-retired generation adds no new generation.
    taskExecutionService.supersedeFrom(aOldId);
    assertEquals(2, taskRunRepository.findByNameAndWorkflowRunRef("a", wfRunId).size());

    // H15: the default WorkflowRun response never shows superseded generations.
    List<TaskRun> defaultTasks = workflowRunService.get(wfRunId, true).getTasks();
    assertEquals(4, defaultTasks.size(), "default response is one live TaskRun per node");
  }

  @Test
  void retryMigratesLineageToTypedFieldsNotAnnotations() {
    String workflowId = createLinearWorkflow("retry-lineage");
    String wfRunId = runToCompletion(workflowId);

    WorkflowRun retryRun = workflowRunService.retry(wfRunId, false, 2);

    WorkflowRunEntity retry = workflowRunRepository.findById(retryRun.getId()).orElseThrow();
    assertEquals(wfRunId, retry.getInitiatedByRef());
    assertEquals(TriggerEnum.retry.getTrigger(), retry.getTrigger());
    assertEquals(2L, retry.getRetryCount());
    assertNull(retry.getTimeoutCause());
    assertFalse(retry.getAnnotations().containsKey("boomerang.io/retry-of"));
    assertFalse(retry.getAnnotations().containsKey("boomerang.io/retry-count"));
    assertFalse(retry.getLabels().containsKey("boomerang.io/retry-of"));
  }

  @Test
  void workflowTimeoutRecordsTypedCauseNotAnnotation() {
    String workflowId = createLinearWorkflow("timeout-cause");
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("the run to be running")
        .untilAsserted(
            () ->
                assertEquals(
                    RunPhase.running,
                    workflowRunRepository.findById(wfRunId).orElseThrow().getPhase()));

    workflowRunService.timeout(wfRunId, true);

    awaitEngine("the run to time out through the typed cause path")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.timedout, after.getStatus());
              assertEquals(TimeoutCause.task, after.getTimeoutCause());
              assertEquals("A TaskRun exceeded it's timeout.", after.getStatusMessage());
              assertFalse(after.getAnnotations().containsKey("boomerang.io/timeout-cause"));
            });
  }

  private TaskRunEntity liveByName(String name, String wfRunId) {
    return taskRunRepository
        .findFirstByNameAndWorkflowRunRefAndSupersededAtIsNull(name, wfRunId)
        .orElseThrow();
  }

  private String createLinearWorkflow(String name) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask("a", TaskType.template, templateId, "start"),
            workflowTask("b", TaskType.template, templateId, "a"),
            workflowTask("end", TaskType.end, null, "b")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private String runToCompletion(String workflowId) {
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    completeTemplateTask("a", wfRunId);
    completeTemplateTask("b", wfRunId);
    awaitEngine("the run to complete")
        .untilAsserted(
            () ->
                assertEquals(
                    RunPhase.completed,
                    workflowRunRepository.findById(wfRunId).orElseThrow().getPhase()));
    workflowRunService.finalize(wfRunId);
    return wfRunId;
  }

  private void completeTemplateTask(String name, String wfRunId) {
    awaitEngine(name + " ready for pickup")
        .untilAsserted(
            () -> assertEquals(RunStatus.ready, liveByName(name, wfRunId).getStatus()));
    String taskRunId = liveByName(name, wfRunId).getId();
    taskRunService.start(taskRunId, Optional.empty());
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    taskRunService.end(taskRunId, Optional.of(endRequest));
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }
}
