package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A sleep parks for its duration in whole milliseconds. A duration that is missing, not a whole
 * number, negative or too large to hold fails the task with a message saying so, rather than
 * leaving it running.
 */
class SleepDurationTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;

  private String sleepTask;

  @BeforeEach
  void sleepTask() {
    Task task = new Task();
    task.setName("sleep-duration-" + System.nanoTime());
    task.setType(TaskType.sleep);
    AbstractParam duration = new AbstractParam();
    duration.setName("duration");
    duration.setType("text");
    task.getSpec().setParams(new ArrayList<>(List.of(duration)));
    sleepTask = taskService.create(task).getId();
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "1.5", "-5", "99999999999999999999"})
  void aDurationThatIsNotWholeMillisecondsFailsTheTask(String duration) {
    String runId = run(duration);
    awaitEngine("the sleep with duration '" + duration + "' failed")
        .untilAsserted(
            () -> {
              TaskRunEntity nap =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("nap", runId).orElseThrow();
              assertThat(nap.getStatus()).isEqualTo(RunStatus.failed);
              assertThat(nap.getStatusMessage())
                  .isEqualTo("sleep requires a duration in whole milliseconds, got '" + duration + "'.");
            });
  }

  @Test
  void aMissingDurationFailsTheTask() {
    String runId = run(null);
    awaitEngine("the sleep with no duration failed")
        .untilAsserted(
            () -> {
              TaskRunEntity nap =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("nap", runId).orElseThrow();
              assertThat(nap.getStatus()).isEqualTo(RunStatus.failed);
              assertThat(nap.getStatusMessage()).contains("got ''");
            });
  }

  @Test
  void aWholeMillisecondDurationParksTheTaskAsWaiting() {
    String runId = run("600000");
    awaitEngine("the sleep parked as waiting")
        .untilAsserted(
            () -> {
              TaskRunEntity nap =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("nap", runId).orElseThrow();
              assertThat(nap.getStatus()).isEqualTo(RunStatus.waiting);
              assertThat(nap.getStatusMessage()).isNull();
            });
    workflowRunService.cancel(runId);
  }

  // start -> nap (sleep with the given duration, or none) -> end.
  private String run(String duration) {
    Workflow workflow = new Workflow();
    workflow.setName("sleep-duration-" + System.nanoTime());
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask nap = node("nap", TaskType.sleep, sleepTask, "start");
    nap.setParams(
        duration == null
            ? new LinkedList<>()
            : new LinkedList<>(List.of(new RunParam("duration", duration))));
    WorkflowTask end = node("end", TaskType.end, null, "nap");
    workflow.setTasks(new ArrayList<>(List.of(start, nap, end)));
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(runId, Optional.empty());
    return runId;
  }

  private static WorkflowTask node(String name, TaskType type, String taskRef, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(dependsOn);
    task.setDependencies(new LinkedList<>(List.of(dependency)));
    return task;
  }
}
