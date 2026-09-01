package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunEndRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The engine-enforced payload caps: resolved params are checked at
 * admission, results at end. Both are engine-side so the failure mode is one clear message on
 * every executor, instead of each substrate's own ceiling (128 KiB env string at exec, 4096-byte
 * termination message, 4 KB Lambda env) surfacing differently.
 */
class PayloadCapTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;

  @MockitoSpyBean private DAGUtility dagUtility;

  @Test
  void oversizeResolvedParamsInvalidateTheTaskBeforeAdmission() {
    WorkflowRunEntity wfRun = savedWorkflowRun("params-cap-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            "big-params",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    // Over the 16384-byte default once serialized.
    task.setParams(List.of(new RunParam("blob", "x".repeat(20000), ParamType.string)));
    taskRunRepository.save(task);
    String taskId = task.getId();

    doAnswer(invocation -> true).when(dagUtility).canRunTask(anyList(), any());

    taskExecutionService.queue(taskId);

    awaitEngine("the oversize task to be invalidated and completed")
        .until(() -> RunPhase.completed.equals(current(taskId).getPhase()));

    TaskRunEntity after = current(taskId);
    assertEquals(
        RunStatus.invalid,
        after.getStatus(),
        "an oversize task must be invalidated, never admitted as claimable");
    assertTrue(
        after.getStatusMessage().contains("PARAMS_TOO_LARGE"),
        "the failure must name the cap; got: " + after.getStatusMessage());
  }

  @Test
  void oversizeResultsFailTheTaskAtEnd() {
    WorkflowRunEntity wfRun = savedWorkflowRun("results-cap-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            "big-results",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    String taskId = task.getId();

    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    // Over the 4096-byte default once serialized.
    endRequest.setResults(List.of(new RunResult("big", "x".repeat(8000))));

    taskRunService.end(taskId, Optional.of(endRequest));

    awaitEngine("the oversize-results task to complete")
        .until(() -> RunPhase.completed.equals(current(taskId).getPhase()));

    TaskRunEntity after = current(taskId);
    assertEquals(
        RunStatus.failed,
        after.getStatus(),
        "a task reporting oversize results must fail, not succeed with them dropped silently");
    assertTrue(
        after.getStatusMessage().contains("RESULTS_TOO_LARGE"),
        "the failure must name the cap; got: " + after.getStatusMessage());
    assertTrue(
        after.getResults() == null || after.getResults().isEmpty(),
        "the oversize results must not be persisted; got: " + after.getResults());
    assertEquals(
        "ResultsTooLarge",
        after.getStatusReason(),
        "the typed cause must record why the task failed, alongside the human message");
  }

  private TaskRunEntity current(String id) {
    return taskRunRepository.findById(id).orElseThrow();
  }
}
