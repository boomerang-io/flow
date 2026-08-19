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
 * timeout reap only ever selects on {@code timeoutAt}. That deadline is the task's real budget
 * (claimedAt + effectiveTimeout + grace), computed once at claim time - not an arbitrary window
 * later replaced at start.
 */
class ClaimTimeoutReapTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowWatcher watcher;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void claimBakesTheTasksRealDeadlineAtClaimTime() {
    WorkflowRunEntity wfRun = savedWorkflowRun("claim-deadline-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "claimable",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    taskRun.setTimeout(45L);
    taskRunRepository.save(taskRun);

    Date beforeClaim = new Date();
    TaskRunEntity claimed = taskRunService.tryClaim(taskRun.getId(), "some-dispatcher");

    assertNotNull(claimed);
    assertEquals(RunPhase.queued, claimed.getPhase());
    assertNotNull(claimed.getTimeoutAt(), "the claim must bake the task's real deadline");

    // The task's own 45-minute budget, not a fixed claim-to-start window - comfortably past
    // where a 10-minute provisional constant would have landed.
    Date fortyMinutesOut = new Date(beforeClaim.getTime() + 40 * 60000);
    Date fiftyMinutesOut = new Date(beforeClaim.getTime() + 50 * 60000);
    assertTrue(
        claimed.getTimeoutAt().after(fortyMinutesOut),
        "the deadline must reflect the task's own 45-minute budget, not a short provisional one");
    assertTrue(
        claimed.getTimeoutAt().before(fiftyMinutesOut),
        "the deadline must not run away past the task's own budget plus grace");

    TaskRunEntity persisted = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(
        claimed.getTimeoutAt(),
        persisted.getTimeoutAt(),
        "the deadline must be durable, not just in-memory");
  }

  @Test
  void aTaskStillWithinItsClaimedBudgetIsNotReaped() {
    WorkflowRunEntity wfRun = savedWorkflowRun("claim-in-budget-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "in-budget",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    taskRun.setTimeout(30L);
    taskRunRepository.save(taskRun);

    taskRunService.tryClaim(taskRun.getId(), "healthy-dispatcher");

    // A dispatcher that is merely slow (e.g. still pulling an image) must not be reaped just
    // because it has not yet reported starting - it is well inside its real budget.
    watcher.reapTaskTimeouts();

    TaskRunEntity untouched = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunPhase.queued, untouched.getPhase(), "a claim within budget must survive the sweep");
    assertNotNull(untouched.getClaim().getBy(), "the claim must not be released early");
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
    taskRun.setTimeout(30L);
    taskRunRepository.save(taskRun);

    // The real claim path - no manual field poking - bakes the task's real deadline.
    taskRunService.tryClaim(taskRun.getId(), "dispatcher-that-then-dies");

    // The dispatcher vanishes before ever calling start(): fast-forward the deadline to
    // reproduce a claim that has sat unstarted past its budget.
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
  void startExecutionRebakesTheDeadlineFromTheActualStartTime() {
    WorkflowRunEntity wfRun = savedWorkflowRun("claim-start-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "budgeted",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    taskRun.setTimeout(5L);
    taskRunRepository.save(taskRun);

    TaskRunEntity claimed = taskRunService.tryClaim(taskRun.getId(), "dispatcher-1");
    Date claimDeadline = claimed.getTimeoutAt();
    assertNotNull(claimDeadline);

    // Execution actually starts later, with a larger real budget than what was baked at claim -
    // starting must rebake from the actual start time, not keep measuring from claim time.
    Date startTime = new Date();
    TaskRunEntity started = taskRunService.tryStartExecution(taskRun.getId(), startTime, 120L);

    assertNotNull(started, "starting a freshly-claimed task must win the Compare-And-Set");
    assertNotEquals(
        claimDeadline,
        started.getTimeoutAt(),
        "the task's execution deadline must be rebaked from the actual start time");
    assertTrue(
        started.getTimeoutAt().after(claimDeadline),
        "a 120-minute budget from start must push the deadline out past the 5-minute claim deadline");

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
