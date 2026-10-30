package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ExecutionCondition;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A finally task is a task whose single dependency is {@code end} with the {@code finally}
 * condition. Once the graph has ended the run takes its final status while staying in the running
 * phase, the finally tasks run in parallel with that status in reach, and the run completes when
 * the last of them ends. A finally task that fails turns a succeeded run into failed, and a cancel
 * ends the graph but still runs them. Saving holds a finally task to that one dependency and lets
 * nothing depend on it.
 */
class FinallyTaskTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowWatcher workflowWatcher;

  private String sleepTask;
  private String setPropertyTask;

  @BeforeEach
  void engineRunTasks() {
    sleepTask = globalTask("finally-sleep", TaskType.sleep, "duration");
    setPropertyTask = globalTask("finally-set", TaskType.setwfproperty, "output", "value");
  }

  @Test
  void finallyTasksRunAfterTheGraphSucceededAndTheRunCompletesWhenTheyEnd() {
    Workflow workflow = graph("fin-succeeded", setProperty("work", "a", "1", "start"));
    workflow.getTasks().add(finallyTask(sleep("hold", "1500")));
    workflow.getTasks().add(finallyTask(setProperty("note", "ended", "$(context.params.workflowrun-status)")));
    String runId = run(workflow);

    awaitEngine("the graph ended succeeded while the finally tasks run")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
              assertThat(run.getStatus()).isEqualTo(RunStatus.succeeded);
              assertThat(run.getPhase()).isEqualTo(RunPhase.running);
              assertThat(run.getTimeoutAt()).as("the finally stage has its own deadline").isNotNull();
            });
    // The sleep parks as waiting; the watcher's due-wait sweep, run by hand here, resumes it.
    awaitEngine("the run completed once the last finally task ended")
        .untilAsserted(
            () -> {
              workflowWatcher.resumeDueWaitingTasks();
              WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
              assertThat(run.getPhase()).isEqualTo(RunPhase.completed);
              assertThat(run.getStatus()).isEqualTo(RunStatus.succeeded);
            });
    assertThat(taskRun(runId, "hold").getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(taskRun(runId, "note").getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(resultValue(runId, "ended"))
        .as("a finally task reads the run's final status")
        .isEqualTo("succeeded");
  }

  @Test
  void aFailedFinallyTaskTurnsASucceededRunIntoFailed() {
    Workflow workflow = graph("fin-failed", setProperty("work", "a", "1", "start"));
    // An unresolved reference fails the task at admission, which is a failed finally task.
    workflow.getTasks().add(finallyTask(setProperty("broken", "b", "$(tasks.nothing.results.x)")));
    workflow.getTasks().add(finallyTask(setProperty("note", "ended", "$(context.params.workflowrun-status)")));
    String runId = run(workflow);

    awaitEngine("the run completed after its finally tasks")
        .untilAsserted(
            () ->
                assertThat(workflowRunRepository.findById(runId).orElseThrow().getPhase())
                    .isEqualTo(RunPhase.completed));
    WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
    assertThat(run.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(run.getStatusReason()).isEqualTo(StatusReason.FINALLY_FAILED);
    assertThat(taskRun(runId, "broken").getStatus()).isEqualTo(RunStatus.failed);
    assertThat(taskRun(runId, "note").getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(resultValue(runId, "ended")).isEqualTo("succeeded");
  }

  @Test
  void aCancelEndsTheGraphAndStillRunsTheFinallyTasks() {
    Workflow workflow = graph("fin-cancelled", sleep("work", "60000", "start"));
    workflow.getTasks().add(finallyTask(setProperty("note", "ended", "$(context.params.workflowrun-status)")));
    String runId = run(workflow);
    awaitEngine("the graph's task is in flight")
        .untilAsserted(
            () -> assertThat(taskRun(runId, "work").getPhase()).isEqualTo(RunPhase.running));

    workflowRunService.cancel(runId);

    awaitEngine("the run completed cancelled after its finally task")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
              assertThat(run.getPhase()).isEqualTo(RunPhase.completed);
              assertThat(run.getStatus()).isEqualTo(RunStatus.cancelled);
            });
    assertThat(taskRun(runId, "work").getStatus()).isEqualTo(RunStatus.cancelled);
    assertThat(taskRun(runId, "note").getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(resultValue(runId, "ended")).isEqualTo("cancelled");
  }

  @Test
  void aFinallyDependencyIsOnEndAloneAndNothingDependsOnAFinallyTask() {
    Workflow notOnEnd = graph("fin-not-end", setProperty("work", "a", "1", "start"));
    WorkflowTask wrong = setProperty("after-work", "b", "1", "work");
    wrong.getDependencies().get(0).setExecutionCondition(ExecutionCondition.Finally);
    notOnEnd.getTasks().add(wrong);
    assertThatThrownBy(() -> workflowService.create(notOnEnd, false))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_INVALID_FINALLY.getCode());

    Workflow chained = graph("fin-chained", setProperty("work", "a", "1", "start"));
    chained.getTasks().add(finallyTask(setProperty("first", "b", "1")));
    chained.getTasks().add(setProperty("second", "c", "1", "first"));
    assertThatThrownBy(() -> workflowService.create(chained, false))
        .isInstanceOf(BoomerangException.class)
        .extracting(e -> ((BoomerangException) e).getCode())
        .isEqualTo(BoomerangError.WORKFLOW_INVALID_FINALLY.getCode());
  }

  private String run(Workflow workflow) {
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(runId, Optional.empty());
    return runId;
  }

  // start -> work -> end, with the given work task.
  private static Workflow graph(String name, WorkflowTask work) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    end.setDependencies(new LinkedList<>(List.of(dependency(work.getName(), ExecutionCondition.always))));
    workflow.setTasks(new ArrayList<>(List.of(start, work, end)));
    return workflow;
  }

  private static WorkflowTask finallyTask(WorkflowTask task) {
    task.setDependencies(new LinkedList<>(List.of(dependency("end", ExecutionCondition.Finally))));
    return task;
  }

  private WorkflowTask sleep(String name, String millis, String... dependsOn) {
    WorkflowTask task = node(name, TaskType.sleep, sleepTask, dependsOn);
    task.setParams(new LinkedList<>(List.of(new RunParam("duration", millis))));
    return task;
  }

  private WorkflowTask setProperty(String name, String output, String value, String... dependsOn) {
    WorkflowTask task = node(name, TaskType.setwfproperty, setPropertyTask, dependsOn);
    task.setParams(
        new LinkedList<>(List.of(new RunParam("output", output), new RunParam("value", value))));
    return task;
  }

  private static WorkflowTask node(String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    List<WorkflowTaskDependency> dependencies = new LinkedList<>();
    for (String ref : dependsOn) {
      dependencies.add(dependency(ref, ExecutionCondition.always));
    }
    task.setDependencies(dependencies);
    return task;
  }

  private static WorkflowTaskDependency dependency(String taskRef, ExecutionCondition condition) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    dependency.setExecutionCondition(condition);
    return dependency;
  }

  private String globalTask(String name, TaskType type, String... params) {
    Task task = new Task();
    task.setName(name + "-" + System.nanoTime());
    task.setType(type);
    List<AbstractParam> declared = new ArrayList<>();
    for (String param : params) {
      AbstractParam declaredParam = new AbstractParam();
      declaredParam.setName(param);
      declaredParam.setType("text");
      declared.add(declaredParam);
    }
    task.getSpec().setParams(declared);
    // The internal create keeps the id, which the internal workflow create wants as the reference.
    return taskService.create(task).getId();
  }

  private TaskRunEntity taskRun(String runId, String name) {
    return taskRunRepository.findFirstByNameAndWorkflowRunRef(name, runId).orElseThrow();
  }

  private String resultValue(String runId, String name) {
    return workflowRunRepository.findById(runId).orElseThrow().getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .map(String::valueOf)
        .findFirst()
        .orElse(null);
  }
}
