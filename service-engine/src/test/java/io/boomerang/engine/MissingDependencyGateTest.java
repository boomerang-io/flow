package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.WorkflowTaskDependency;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every DAG node is materialised as a TaskRun at queue time, so a dependency without a TaskRun
 * document is a broken invariant: the dependant must never be treated as executable.
 */
class MissingDependencyGateTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;

  @Test
  void missingDependencyTaskRunBlocksExecution() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("missing-dep-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity upstream =
        savedTaskRun(
            "upstream",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    TaskRunEntity gated =
        savedTaskRun(
            "gated",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    gated.setDependencies(List.of(dependencyOn("upstream"), dependencyOn("ghost")));
    taskRunRepository.save(gated);

    // Ending the upstream task advances the graph asynchronously. The dependant declares a
    // dependency on a task with no TaskRun document, so it must never be queued.
    taskExecutionService.end(upstream.getId());

    awaitEngine("upstream end to complete")
        .until(
            () ->
                RunPhase.completed.equals(
                    taskRunRepository.findById(upstream.getId()).orElseThrow().getPhase()));

    // Hold the gate assertion through a settle window so any stray async queue would be caught.
    awaitEngine("dependant to remain gated")
        .during(Duration.ofSeconds(2))
        .until(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(gated.getId()).orElseThrow();
              return RunStatus.notstarted.equals(after.getStatus())
                  && RunPhase.pending.equals(after.getPhase());
            });
    TaskRunEntity after = taskRunRepository.findById(gated.getId()).orElseThrow();
    assertEquals(RunStatus.notstarted, after.getStatus());
    assertEquals(RunPhase.pending, after.getPhase());
  }

  private static WorkflowTaskDependency dependencyOn(String taskRef) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    return dependency;
  }
}
