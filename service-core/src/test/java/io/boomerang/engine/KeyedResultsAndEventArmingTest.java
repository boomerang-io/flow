package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Two field-scoped write paths that used to lose data: a workflow result written twice under one
 * key appended a duplicate (issue #241), and an eventwait armed with a whole-document save rolled
 * back an event that had already been delivered. Both drive the real {@code
 * TaskExecutionService.execute}.
 */
class KeyedResultsAndEventArmingTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @MockitoSpyBean private TaskRunService spiedTaskRunService;

  @Test
  void secondSetResultParamWithSameKeyUpdatesRatherThanDuplicates() {
    WorkflowRunEntity wfRun = savedWorkflowRun("dup-key-wf", RunStatus.running, RunPhase.running);
    String first = savedSetResultTask(wfRun, "set-first", "dup-key", "first");
    String second = savedSetResultTask(wfRun, "set-second", "dup-key", "second");

    taskExecutionService.execute(first);
    awaitEngine("the first setwfproperty to complete")
        .until(() -> RunPhase.completed.equals(current(first).getPhase()));
    taskExecutionService.execute(second);
    awaitEngine("the second setwfproperty to complete")
        .until(() -> RunPhase.completed.equals(current(second).getPhase()));

    List<RunResult> results = workflowRunRepository.findById(wfRun.getId()).orElseThrow().getResults();
    assertEquals(
        1,
        results.stream().filter(r -> "dup-key".equals(r.getName())).count(),
        "a result key written twice must stay unique (issue #241); got " + results);
    assertEquals("second", results.get(0).getValue(), "the later write updates the value");
  }

  @Test
  void resultsWithDifferentKeysAreBothKept() {
    WorkflowRunEntity wfRun = savedWorkflowRun("two-keys-wf", RunStatus.running, RunPhase.running);
    String a = savedSetResultTask(wfRun, "set-a", "key-a", "A");
    String b = savedSetResultTask(wfRun, "set-b", "key-b", "B");

    taskExecutionService.execute(a);
    taskExecutionService.execute(b);
    awaitEngine("both setwfproperty tasks to complete")
        .until(
            () ->
                RunPhase.completed.equals(current(a).getPhase())
                    && RunPhase.completed.equals(current(b).getPhase()));

    List<RunResult> results = workflowRunRepository.findById(wfRun.getId()).orElseThrow().getResults();
    assertEquals(2, results.size(), "distinct keys are never merged; got " + results);
  }

  /**
   * The event lands between execute()'s entry read and the arm - the seam a second instance's
   * delivery crosses in production. Arming used to save the stale entity whole, reverting {@code
   * preApproved}, the status annotation and the results, and the task then waited for an event
   * that had already come and gone.
   */
  @Test
  void eventDeliveredWhileArmingIsHonouredNotLost() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("arm-race-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            "wait-for-build",
            TaskType.eventwait,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    task.setParams(List.of(new RunParam("topic", "build-complete", ParamType.string)));
    taskRunRepository.save(task);
    String taskRunId = task.getId();

    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (injected.compareAndSet(false, true)) {
                taskRunService.applyEventDelivery(
                    taskRunId,
                    RunStatus.succeeded,
                    List.of(new RunResult("data", "{\"build\":\"ok\"}")));
              }
              return invocation.callRealMethod();
            })
        .when(spiedTaskRunService)
        .tryArmEventWait(any());

    taskExecutionService.execute(taskRunId);

    awaitEngine("the pre-approved eventwait to end with the delivered status")
        .untilAsserted(
            () -> {
              TaskRunEntity after = current(taskRunId);
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.succeeded, after.getStatus());
            });
    TaskRunEntity after = current(taskRunId);
    assertTrue(after.isPreApproved(), "the delivery's preApproved flag must survive arming");
    assertEquals(
        1,
        after.getResults().stream().filter(r -> "data".equals(r.getName())).count(),
        "the delivered result must survive arming; got " + after.getResults());
  }

  private String savedSetResultTask(
      WorkflowRunEntity wfRun, String name, String output, String value) {
    TaskRunEntity task =
        savedTaskRun(
            name,
            TaskType.setwfproperty,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    task.setParams(
        List.of(
            new RunParam("output", output, ParamType.string),
            new RunParam("value", value, ParamType.string)));
    return taskRunRepository.save(task).getId();
  }

  private TaskRunEntity current(String id) {
    return taskRunRepository.findById(id).orElseThrow();
  }
}
