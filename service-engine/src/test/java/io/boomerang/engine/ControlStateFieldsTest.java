package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Task;
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
 * The control-state fields that replaced the boomerang.io/* orchestration annotations (DD-08):
 * retry lineage on initiatedByRef + trigger, retryCount, and the timeout cause carried straight
 * into statusMessage. Asserted on the retry and timeout paths directly.
 */
class ControlStateFieldsTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;

  @Test
  void retryMigratesLineageToTypedFieldsNotAnnotations() {
    String workflowId = createLinearWorkflow("retry-lineage");
    String wfRunId = runToCompletion(workflowId);

    WorkflowRun retryRun = workflowRunService.retry(wfRunId, false, 2);

    WorkflowRunEntity retry = workflowRunRepository.findById(retryRun.getId()).orElseThrow();
    assertEquals(wfRunId, retry.getInitiatedByRef());
    assertEquals(TriggerEnum.retry.getTrigger(), retry.getTrigger());
    assertEquals(2L, retry.getRetryCount());
    assertFalse(retry.getAnnotations().containsKey("boomerang.io/retry-of"));
    assertFalse(retry.getAnnotations().containsKey("boomerang.io/retry-count"));
    assertFalse(retry.getLabels().containsKey("boomerang.io/retry-of"));
  }

  @Test
  void workflowTimeoutRecordsCauseInStatusMessageNotAnnotation() {
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

    awaitEngine("the run to time out with the cause in its status message")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.timedout, after.getStatus());
              assertEquals("A TaskRun exceeded it's timeout.", after.getStatusMessage());
              assertFalse(after.getAnnotations().containsKey("boomerang.io/timeout-cause"));
            });
  }

  private TaskRunEntity liveByName(String name, String wfRunId) {
    return taskRunRepository.findFirstByNameAndWorkflowRunRef(name, wfRunId).orElseThrow();
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
            () -> assertTrue(RunStatus.ready.equals(liveByName(name, wfRunId).getStatus())));
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
