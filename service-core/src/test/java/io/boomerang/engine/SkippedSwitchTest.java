package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ExecutionCondition;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.workflow.WorkflowService;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A switch that never ran made no decision, so it takes no branch. The graph under test: start ->
 * check -(on failure)-> fix, whose branches "retry" -> retry-task and "abort" -> abort-task both
 * lead to end.
 */
class SkippedSwitchTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;

  @Test
  void aSwitchThatNeverRanTakesNoBranchAndTheRunCompletes() {
    String runId =
        workflowService.submit(createWorkflow(), new WorkflowSubmitRequest(), true).getId();

    // check succeeds, so the failure edge into fix is not taken and fix is skipped unrun.
    awaitEngine("check is admitted")
        .until(() -> taskRunRepository.findFirstByNameAndWorkflowRunRef("check", runId).isPresent());
    TaskRunEndRequest succeeded = new TaskRunEndRequest();
    succeeded.setStatus(RunStatus.succeeded);
    taskRunService.end(
        taskRunRepository.findFirstByNameAndWorkflowRunRef("check", runId).orElseThrow().getId(),
        Optional.of(succeeded));

    awaitEngine("the run completes past the skipped switch")
        .until(
            () ->
                RunPhase.completed.equals(
                    workflowRunRepository.findById(runId).map(WorkflowRunEntity::getPhase).orElse(null)));
    for (String name : List.of("fix", "retry-task", "abort-task")) {
      TaskRunEntity task =
          taskRunRepository.findFirstByNameAndWorkflowRunRef(name, runId).orElseThrow();
      assertEquals(RunStatus.skipped, task.getStatus(), name + " is skipped");
    }
  }

  private String createWorkflow() {
    Task template = new Task();
    template.setName("skipped-switch-echo-" + System.nanoTime());
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();
    Task decision = new Task();
    decision.setName("skipped-switch-decision-" + System.nanoTime());
    decision.setType(TaskType.decision);
    String decisionId = taskService.create(decision).getId();

    WorkflowTask fix = node("fix", TaskType.decision, decisionId, dependency("check", ExecutionCondition.failure, null));
    fix.getParams().add(new RunParam("value", "retry"));
    Workflow workflow = new Workflow();
    workflow.setName("skipped-switch-" + System.nanoTime());
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                node("start", TaskType.start, null),
                node("check", TaskType.template, templateId, dependency("start", null, null)),
                fix,
                node("retry-task", TaskType.template, templateId, dependency("fix", null, "retry")),
                node("abort-task", TaskType.template, templateId, dependency("fix", null, "abort")),
                node(
                    "end",
                    TaskType.end,
                    null,
                    dependency("retry-task", ExecutionCondition.always, null),
                    dependency("abort-task", ExecutionCondition.always, null)))));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private static WorkflowTask node(
      String name, TaskType type, String taskRef, WorkflowTaskDependency... dependencies) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    task.setDependencies(new LinkedList<>(List.of(dependencies)));
    return task;
  }

  private static WorkflowTaskDependency dependency(
      String taskRef, ExecutionCondition condition, String decisionCondition) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    if (condition != null) {
      dependency.setExecutionCondition(condition);
    }
    dependency.setDecisionCondition(decisionCondition);
    return dependency;
  }
}
