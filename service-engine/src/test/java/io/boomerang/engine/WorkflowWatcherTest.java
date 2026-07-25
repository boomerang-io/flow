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
import io.boomerang.common.model.WorkflowTaskDependency;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The watcher's self-healing sweeps: durable timeout reaping with retry backoff (which is also
 * the crash recovery for a killed claimant - one sweep pass recovers the task), stalled-run
 * recovery, and engine-internal finalize of workspace-less completed runs. Every sweep action
 * goes through a Compare-And-Set, so the sweeps are exercised by direct invocation - one call is
 * one tick.
 */
class WorkflowWatcherTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowWatcher watcher;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void killedClaimantTaskIsRequeuedByOneSweep() {
    WorkflowRunEntity wfRun = savedWorkflowRun("reap-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "killed-claimant",
                TaskType.template,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    // A claim whose agent died: claimed, deadline elapsed, never completed.
    claimWithExpiredDeadline(taskRunId, "dead-agent", 1L);

    watcher.reapTaskTimeouts();

    TaskRunEntity requeued = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunStatus.ready, requeued.getStatus());
    assertEquals(RunPhase.pending, requeued.getPhase());
    assertNull(requeued.getClaim().getBy(), "requeue must clear the claim ownership");
    assertEquals(1L, requeued.getClaim().getSeq(), "requeue must never clear claim.seq");
    assertNull(requeued.getAgentRef());
    assertNull(requeued.getTimeoutAt(), "the deadline is baked at the next execution start");
    assertNotNull(requeued.getRetry());
    assertEquals(1, requeued.getRetry().getCount());
    assertTrue(
        requeued.getRetry().getAfter().after(new Date()),
        "the retry backoff must gate the next attempt");

    // The claim page honours the backoff: excluded until retry.after elapses.
    assertFalse(claimPageContains(taskRunId));
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("retry.after", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);
    assertTrue(claimPageContains(taskRunId));
  }

  @Test
  void timeoutReapContinuesTheRetryCount() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("recount-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "recount",
                TaskType.template,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    claimWithExpiredDeadline(taskRunId, "dead-agent", 3L);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("retry.count", 1),
        TaskRunEntity.class);

    watcher.reapTaskTimeouts();

    TaskRunEntity requeued = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunStatus.ready, requeued.getStatus());
    assertEquals(2, requeued.getRetry().getCount());
    assertEquals(3L, requeued.getClaim().getSeq());
  }

  @Test
  void exhaustedRetryBudgetTimesOutTaskAndRun() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("exhausted-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "exhausted",
                TaskType.template,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    claimWithExpiredDeadline(taskRunId, "dead-agent", 2L);
    // All retry attempts consumed - the reap goes terminal through the normal end path.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("retry.count", 3),
        TaskRunEntity.class);

    watcher.reapTaskTimeouts();

    awaitEngine("the timed-out task to complete through the end path")
        .untilAsserted(
            () -> {
              TaskRunEntity reaped = taskRunRepository.findById(taskRunId).orElseThrow();
              assertEquals(RunStatus.timedout, reaped.getStatus());
              assertEquals(RunPhase.completed, reaped.getPhase());
              assertNotNull(
                  reaped.getStatusMessage(),
                  "the timeout reason is written atomically with the status");
            });
    awaitEngine("the final task timeout to time out the run")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.timedout,
                    workflowRunRepository.findById(wfRun.getId()).orElseThrow().getStatus()));
  }

  @Test
  void healthyInBudgetTaskIsNotReaped() {
    WorkflowRunEntity wfRun = savedWorkflowRun("healthy-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "healthy",
                TaskType.template,
                RunStatus.running,
                RunPhase.running,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    Date futureDeadline = new Date(System.currentTimeMillis() + 600000);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("timeoutAt", futureDeadline),
        TaskRunEntity.class);

    watcher.reapTaskTimeouts();

    TaskRunEntity untouched = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunStatus.running, untouched.getStatus());
    assertEquals(RunPhase.running, untouched.getPhase());
    assertEquals(futureDeadline, untouched.getTimeoutAt());
  }

  @Test
  void stalledRunWithNoInFlightTasksIsRecovered() {
    WorkflowRunEntity wfRun = savedWorkflowRun("stalled-wf", RunStatus.running, RunPhase.running);
    wfRun.setStartTime(new Date(System.currentTimeMillis() - 120000));
    workflowRunRepository.save(wfRun);
    savedTaskRun(
        "start",
        TaskType.start,
        RunStatus.succeeded,
        RunPhase.completed,
        wfRun.getWorkflowRef(),
        wfRun.getId());
    // The advancing winner completed this task and crashed before advancing the graph.
    TaskRunEntity work =
        savedTaskRun(
            "work",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    work.setDependencies(List.of(dependencyOn("start")));
    taskRunRepository.save(work);
    TaskRunEntity end =
        savedTaskRun(
            "end",
            TaskType.end,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    end.setDependencies(List.of(dependencyOn("work")));
    taskRunRepository.save(end);

    watcher.recoverStalledRuns();

    awaitEngine("the recovered advance to finish the run")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
              assertEquals(RunStatus.succeeded, after.getStatus());
              assertEquals(RunPhase.completed, after.getPhase());
            });
    TaskRunEntity endAfter = taskRunRepository.findById(end.getId()).orElseThrow();
    assertEquals(RunStatus.succeeded, endAfter.getStatus());
    assertEquals(RunPhase.completed, endAfter.getPhase());
  }

  @Test
  void runningWorkflowPastDeadlineIsTimedOut() {
    WorkflowRunEntity wfRun = savedWorkflowRun("wf-deadline", RunStatus.running, RunPhase.running);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(wfRun.getId())),
        new Update().set("timeoutAt", new Date(System.currentTimeMillis() - 1000)),
        WorkflowRunEntity.class);

    watcher.reapWorkflowTimeouts();

    awaitEngine("the run past its deadline to be timed out")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
              assertEquals(RunStatus.timedout, after.getStatus());
              assertEquals(RunPhase.completed, after.getPhase());
            });
  }

  @Test
  void workspacelessCompletedRunIsFinalized() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("finalize-wf", RunStatus.succeeded, RunPhase.completed);

    watcher.finalizeWorkspacelessRuns();

    WorkflowRunEntity after = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
    assertEquals(RunPhase.finalized, after.getPhase());
    assertEquals(RunStatus.succeeded, after.getStatus());
  }

  @Test
  void dueSleepTaskIsCompletedBySweepNotAHeldThread() {
    WorkflowRunEntity wfRun = savedWorkflowRun("sleep-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "napper",
                TaskType.sleep,
                RunStatus.waiting,
                RunPhase.running,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    // A durable sleep whose wake time has passed - no thread is blocked, the row is due.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("waitUntil", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);

    watcher.resumeDueWaitingTasks();

    awaitEngine("the due sleep task to complete succeeded")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.succeeded,
                    taskRunRepository.findById(taskRunId).orElseThrow().getStatus()));
  }

  private void claimWithExpiredDeadline(String taskRunId, String claimedBy, long claimSeq) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("claim.seq", claimSeq)
            .set("agentRef", claimedBy)
            .set("timeout", 5L)
            .set("timeoutAt", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);
  }

  private boolean claimPageContains(String taskRunId) {
    return taskRunService.findClaimable(List.of(TaskType.template), 100).stream()
        .anyMatch(t -> taskRunId.equals(t.getId()));
  }

  private static WorkflowTaskDependency dependencyOn(String taskRef) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    return dependency;
  }
}
