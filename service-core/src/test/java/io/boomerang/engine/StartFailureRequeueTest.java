package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.dispatcher.DispatcherService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * A dispatcher that could not START a task - quota refused it, or its pod never left Pending - hands
 * it back: the engine requeues it for another attempt on the timed-out claimant's budget, and fails
 * it with that reason only once the budget is spent. Any other failure still ends the task.
 */
class StartFailureRequeueTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;

  private String registerDispatcher(String name) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name, name + ".local", List.of("template")));
  }

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  // A ready template task whose run is running, claimed by a fresh dispatcher. Returns {taskRunId,
  // dispatcherId}.
  private String[] claimedTask(String tag, int priorAttempts) {
    WorkflowRunEntity wfRun = savedWorkflowRun(tag + "-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            tag + "-task",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    if (priorAttempts > 0) {
      RunRetry retry = new RunRetry();
      retry.setCount(priorAttempts);
      task.setRetry(retry);
      taskRunRepository.save(task);
    }
    String dispatcher = registerDispatcher(tag);
    assertTrue(containsId(dispatcherService.getTaskQueue(dispatcher), task.getId()));
    return new String[] {task.getId(), dispatcher};
  }

  private static TaskRunEndRequest failed(String reason, String dispatcher) {
    TaskRunEndRequest request = new TaskRunEndRequest();
    request.setStatus(RunStatus.failed);
    request.setStatusReason(reason);
    request.setStatusMessage(reason + " - from the dispatcher");
    request.setDispatcherRef(dispatcher);
    return request;
  }

  @Test
  void aQuotaRefusalIsRequeuedForAnotherAttempt() {
    String[] claimed = claimedTask("quota", 0);

    taskRunService.end(claimed[0], Optional.of(failed("ExceededQuota", claimed[1])));

    TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
    assertEquals(RunPhase.pending, after.getPhase());
    assertEquals(1, after.getRetry().getCount());
    assertNotNull(after.getRetry().getAfter());
    assertTrue(after.getRetry().getAfter().after(new Date()), "the backoff gates the next claim");
  }

  @Test
  void aStartTimeoutIsRequeuedAndItsClaimReleasedByTheTerminationOrder() {
    String[] claimed = claimedTask("start-timeout", 0);

    taskRunService.end(claimed[0], Optional.of(failed("StartTimeout", claimed[1])));

    // The claim survives the requeue; the next poll hands it out as a termination order, which
    // releases it so the task is claimable again once its backoff has passed.
    assertTrue(containsId(dispatcherService.getTaskQueue(claimed[1]), claimed[0]));
    TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
    assertEquals(RunStatus.ready, after.getStatus());
    assertEquals(RunPhase.pending, after.getPhase());
    assertEquals(null, after.getClaim().getBy());
  }

  @Test
  void aSpentBudgetFailsTheTaskWithTheReason() {
    String[] claimed = claimedTask("spent", EngineConstants.MAX_RETRIES);

    taskRunService.end(claimed[0], Optional.of(failed("ExceededQuota", claimed[1])));

    awaitEngine("the task to fail once its retry budget is spent")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
              assertEquals("ExceededQuota", after.getStatusReason());
            });
  }

  @Test
  void aFailureWithNoReasonStillEndsTheTask() {
    // Engine-side ends - an action rejected, a child run failed - carry no reason at all.
    String[] claimed = claimedTask("no-reason", 0);

    taskRunService.end(claimed[0], Optional.of(failed(null, claimed[1])));

    awaitEngine("a failure with no reason to end the task")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
            });
  }

  @Test
  void anyOtherFailureStillEndsTheTask() {
    String[] claimed = claimedTask("job-failed", 0);

    taskRunService.end(claimed[0], Optional.of(failed("JobFailed", claimed[1])));

    awaitEngine("an ordinary failure to end the task")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
            });
  }
}
