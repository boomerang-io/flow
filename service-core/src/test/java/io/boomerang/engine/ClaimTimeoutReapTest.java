package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * A claim must bake a durable deadline of its own - otherwise a dispatcher that dies between
 * claiming a TaskRun and reporting its start leaves it stuck in {@code queued} forever, since the
 * timeout reap only ever selects on {@code timeoutAt}.
 */
class ClaimTimeoutReapTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowWatcher watcher;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void claimBakesAProvisionalDeadline() {
    WorkflowRunEntity wfRun = savedWorkflowRun("claim-deadline-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "claimable",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    TaskRunEntity claimed = taskRunService.tryClaim(taskRun.getId(), "some-dispatcher");

    assertNotNull(claimed);
    assertEquals(RunPhase.queued, claimed.getPhase());
    assertNotNull(
        claimed.getTimeoutAt(), "the claim must bake a provisional deadline for the reap sweep");
    assertTrue(
        claimed.getTimeoutAt().after(new Date()),
        "the provisional deadline must sit in the future at claim time");

    TaskRunEntity persisted = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertNotNull(persisted.getTimeoutAt(), "the deadline must be durable, not just in-memory");
  }

  @Test
  void aDispatcherThatDiesBetweenClaimAndStartIsReaped() {
    WorkflowRunEntity wfRun = savedWorkflowRun("dead-claimant-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "orphaned-claim",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    // The real claim path - no manual field poking - bakes the provisional deadline.
    taskRunService.tryClaim(taskRun.getId(), "dispatcher-that-then-dies");

    // The dispatcher vanishes before ever calling start(): fast-forward the deadline to
    // reproduce a claim that has sat unstarted past its provisional budget.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRun.getId())),
        new Update().set("timeoutAt", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);

    watcher.reapTaskTimeouts();

    TaskRunEntity reaped = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(
        RunStatus.ready, reaped.getStatus(), "a requeueable type recovers rather than fails");
    assertEquals(RunPhase.pending, reaped.getPhase());
    assertNull(reaped.getClaim().getBy(), "the stuck claim must be released");
    assertNotNull(reaped.getRetry());
    assertEquals(1, reaped.getRetry().getCount());
  }

  @Test
  void startExecutionReplacesTheProvisionalDeadlineWithTheTasksOwnBudget() {
    WorkflowRunEntity wfRun = savedWorkflowRun("claim-start-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "budgeted",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    TaskRunEntity claimed = taskRunService.tryClaim(taskRun.getId(), "dispatcher-1");
    Date provisionalDeadline = claimed.getTimeoutAt();
    assertNotNull(provisionalDeadline);

    // A budget well past the 10-minute provisional window - starting must replace, not keep,
    // the claim-to-start deadline.
    Date startTime = new Date();
    TaskRunEntity started = taskRunService.tryStartExecution(taskRun.getId(), startTime, 120L);

    assertNotNull(started, "starting a freshly-claimed task must win the Compare-And-Set");
    assertNotEquals(
        provisionalDeadline,
        started.getTimeoutAt(),
        "the task's own budget must replace the provisional claim-to-start deadline");
    assertTrue(
        started.getTimeoutAt().after(provisionalDeadline),
        "a 120-minute budget must push the deadline out past the 10-minute provisional one");

    TaskRunEntity persisted = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(started.getTimeoutAt(), persisted.getTimeoutAt());

    // Not reapable now - its real deadline sits well in the future, so normal work in budget is
    // never caught by the same sweep that recovers a dead claimant.
    watcher.reapTaskTimeouts();
    TaskRunEntity untouched = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.running, untouched.getStatus());
    assertEquals(RunPhase.running, untouched.getPhase());
  }
}
