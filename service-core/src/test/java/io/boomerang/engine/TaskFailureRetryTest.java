package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.BackoffType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RetryBackoff;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.dispatcher.model.DispatchTaskRun;
import io.boomerang.dispatcher.model.TaskRunRetry;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * A task node that asks for retries ({@code retry.max}) has a failure its dispatcher reports
 * requeued on the node's own budget, each superseded attempt recorded on the task run; a cause a
 * retry cannot change, a spent budget, or no setting at all end the task as they always have. The
 * engine's could-not-start requeues keep their separate budget and record their attempts too.
 */
class TaskFailureRetryTest extends AbstractEngineIntegrationTest {

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

  // A ready template task whose run is running, carrying the node's retry setting (none when max
  // is null), claimed by a fresh dispatcher. Returns {taskRunId, dispatcherId}.
  private String[] claimedTask(String tag, Integer retryMax) {
    WorkflowRunEntity wfRun = savedWorkflowRun(tag + "-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            tag + "-task",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    if (retryMax != null) {
      RunRetry retry = new RunRetry();
      retry.setMax(retryMax);
      RetryBackoff backoff = new RetryBackoff();
      backoff.setType(BackoffType.fixed);
      backoff.setInitial(1L);
      retry.setBackoff(backoff);
      task.setRetry(retry);
      taskRunRepository.save(task);
    }
    String dispatcher = registerDispatcher(tag);
    assertTrue(containsId(poll(dispatcher), task.getId()));
    return new String[] {task.getId(), dispatcher};
  }

  private ResponseEntity<List<TaskRun>> poll(String dispatcher) {
    return dispatcherService.getTaskQueue(dispatcher, null, null, null, null);
  }

  private static TaskRunEndRequest failed(String reason, String dispatcher) {
    TaskRunEndRequest request = new TaskRunEndRequest();
    request.setStatus(RunStatus.failed);
    request.setStatusReason(reason);
    request.setStatusMessage(reason + " - from the dispatcher");
    request.setDispatcherRef(dispatcher);
    return request;
  }

  private static TaskRunEndRequest succeeded(String dispatcher) {
    TaskRunEndRequest request = new TaskRunEndRequest();
    request.setStatus(RunStatus.succeeded);
    request.setDispatcherRef(dispatcher);
    return request;
  }

  // After a requeue of a claimed attempt: the holder's next poll carries the termination order
  // and releases the claim; the backoff is then taken as served so the next poll claims again.
  private TaskRun releaseAndReclaim(String taskRunId, String dispatcher) {
    assertTrue(containsId(poll(dispatcher), taskRunId), "the termination order");
    TaskRunEntity released = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunStatus.ready, released.getStatus());
    assertNull(released.getClaim().getBy());
    released.getRetry().setAfter(new Date(System.currentTimeMillis() - 1000));
    taskRunRepository.save(released);
    ResponseEntity<List<TaskRun>> reclaim = poll(dispatcher);
    assertTrue(containsId(reclaim, taskRunId), "the next attempt's claim");
    return reclaim.getBody().stream()
        .filter(t -> taskRunId.equals(t.getId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aReportedFailureIsRetriedWhileTheNodesBudgetLastsAndEachAttemptIsRecorded() {
    String[] claimed = claimedTask("retry-twice", 2);

    taskRunService.end(claimed[0], Optional.of(failed("JobFailed", claimed[1])));

    TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
    assertEquals(RunPhase.pending, after.getPhase());
    assertEquals(RunStatus.waiting, after.getStatus(), "parked with its claim kept");
    assertEquals(1, after.getRetry().getFailures());
    assertEquals(0, after.getRetry().getCount(), "the engine's budget is untouched");
    assertNotNull(after.getRetry().getAfter());
    assertEquals(1, after.getAttempts().size());
    assertEquals(1, after.getAttempts().get(0).getNumber());
    assertEquals(RunStatus.failed, after.getAttempts().get(0).getStatus());
    assertEquals("JobFailed", after.getAttempts().get(0).getStatusReason());
    assertEquals(claimed[1], after.getAttempts().get(0).getDispatcherRef());
    assertEquals(2, new TaskRun(after).getAttempt(), "the public attempt counts the superseded one");

    TaskRun reclaimed = releaseAndReclaim(claimed[0], claimed[1]);
    assertEquals(
        new TaskRunRetry(2, 1),
        DispatchTaskRun.of(reclaimed).retry(),
        "the next order states the node's budget and the one failure spent on it");
    taskRunService.end(claimed[0], Optional.of(failed("JobFailed", claimed[1])));
    after = taskRunRepository.findById(claimed[0]).orElseThrow();
    assertEquals(2, after.getRetry().getFailures());
    assertEquals(2, after.getAttempts().size());

    releaseAndReclaim(claimed[0], claimed[1]);
    taskRunService.end(claimed[0], Optional.of(succeeded(claimed[1])));
    awaitEngine("the task to succeed on its third attempt")
        .untilAsserted(
            () -> {
              TaskRunEntity done = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, done.getPhase());
              assertEquals(RunStatus.succeeded, done.getStatus());
              assertEquals(2, done.getAttempts().size(), "the current attempt is the run itself");
            });
  }

  @Test
  void aSpentNodeBudgetFailsTheTaskWithTheLastAttemptsReason() {
    String[] claimed = claimedTask("retry-once", 1);

    taskRunService.end(claimed[0], Optional.of(failed("JobFailed", claimed[1])));
    releaseAndReclaim(claimed[0], claimed[1]);
    taskRunService.end(claimed[0], Optional.of(failed("OOMKilled", claimed[1])));

    awaitEngine("the task to fail once the node's budget is spent")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
              assertEquals("OOMKilled", after.getStatusReason());
              assertEquals(1, after.getAttempts().size());
            });
  }

  @Test
  void aCauseARetryCannotChangeEndsTheTaskOnItsFirstAttempt() {
    String[] claimed = claimedTask("retry-not-retriable", 2);

    taskRunService.end(claimed[0], Optional.of(failed("ImageNotAllowed", claimed[1])));

    awaitEngine("the task to fail without a retry")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
              assertEquals("ImageNotAllowed", after.getStatusReason());
              assertNull(after.getAttempts());
            });
  }

  @Test
  void aFailureTheDispatcherMarksFinalEndsTheTaskWhateverTheNodeAskedFor() {
    String[] claimed = claimedTask("retry-final", 2);
    TaskRunEndRequest request = failed("JobFailed", claimed[1]);
    request.setRetryable(Boolean.FALSE);

    taskRunService.end(claimed[0], Optional.of(request));

    awaitEngine("the task to fail without a retry")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
              assertEquals("JobFailed", after.getStatusReason());
              assertNull(after.getAttempts());
            });
  }

  @Test
  void aTaskWithoutTheSettingFailsOnItsFirstReportedFailure() {
    String[] claimed = claimedTask("no-retry", null);

    taskRunService.end(claimed[0], Optional.of(failed("JobFailed", claimed[1])));

    awaitEngine("the task to fail as before")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
              assertEquals(RunPhase.completed, after.getPhase());
              assertEquals(RunStatus.failed, after.getStatus());
              assertNull(after.getAttempts());
            });
  }

  @Test
  void aCouldNotStartRequeueRecordsItsAttemptOnTheEnginesBudget() {
    String[] claimed = claimedTask("could-not-start", 2);

    taskRunService.end(claimed[0], Optional.of(failed("ExceededQuota", claimed[1])));

    TaskRunEntity after = taskRunRepository.findById(claimed[0]).orElseThrow();
    assertEquals(RunPhase.pending, after.getPhase());
    assertEquals(1, after.getRetry().getCount());
    assertEquals(0, after.getRetry().getFailures(), "the node's budget is untouched");
    assertEquals(1, after.getAttempts().size());
    assertEquals("ExceededQuota", after.getAttempts().get(0).getStatusReason());
  }
}
