package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.dispatcher.DispatcherService;
import java.util.Date;
import java.util.List;
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
  @Autowired private DispatcherService dispatcherService;

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
    // A requeueable type recovers rather than fails - and because this attempt WAS claimed, it
    // parks awaiting termination of whatever the dead claimant provisioned instead of going
    // straight back on the execution queue. Non-terminal throughout, so the DAG sees nothing.
    assertEquals(RunStatus.waiting, reaped.getStatus());
    assertEquals(RunPhase.pending, reaped.getPhase());
    assertEquals(
        "dispatcher-that-then-dies",
        reaped.getClaim().getBy(),
        "the claim is kept as the marker that a pod may still be out there");
    assertEquals(2L, reaped.getClaim().getSeq(), "the requeue supersedes the reaped claimant");
    assertNull(reaped.getTimeoutAt(), "the stale deadline must be dropped");
    assertNotNull(reaped.getRetry());
    assertEquals(1, reaped.getRetry().getCount());
  }

  // A never-claimed attempt provisioned nothing, so there is no pod to terminate and no reason to
  // park: it goes straight back to ready/pending behind its backoff, as it always has. Parking it
  // would strand a node no agent would ever release.
  @Test
  void anUnclaimedTimeoutRequeuesStraightBackToTheExecutionQueue() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("unclaimed-requeue-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "never-claimed",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    // Running with a blown deadline but no claim - e.g. started through the API rather than by a
    // dispatcher poll.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRun.getId())),
        new Update()
            .set("status", RunStatus.running)
            .set("phase", RunPhase.running)
            .set("timeoutAt", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);

    watcher.reapTaskTimeouts();

    TaskRunEntity reaped = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.ready, reaped.getStatus());
    assertEquals(RunPhase.pending, reaped.getPhase());
    assertEquals(1, reaped.getRetry().getCount());
  }

  // THE HAZARD: a node that times out with retry budget left must not look finished to anyone. It
  // stays non-terminal (DAGUtility keys node completion off RunPhase.completed), it still counts
  // as in flight, and the stall recovery therefore leaves its WorkflowRun alone. A workflow must
  // not fail because one of its tasks is mid-retry.
  @Test
  void aTimeoutWithRetryBudgetLeavesTheWorkflowRunRunning() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("mid-retry-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "mid-retry",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    taskRun.setTimeout(30L);
    taskRunRepository.save(taskRun);
    taskRunService.tryClaim(taskRun.getId(), "dispatcher-mid-retry");
    taskRunService.tryStartExecution(taskRun.getId(), new Date(), 30L);
    blowTheDeadline(taskRun.getId());
    // Old enough for the stall sweep to consider it, so the in-flight guard is genuinely exercised.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(wfRun.getId())),
        new Update().set("startTime", new Date(System.currentTimeMillis() - 120000)),
        WorkflowRunEntity.class);

    watcher.reapTaskTimeouts();

    TaskRunEntity parked = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertNotEquals(
        RunPhase.completed,
        parked.getPhase(),
        "a node mid-retry must never reach the phase the DAG reads as finished");
    assertTrue(
        taskRunService.existsInFlightByWorkflowRunRef(wfRun.getId()),
        "a node mid-retry must still count as in flight");

    watcher.recoverStalledRuns();

    WorkflowRunEntity after = workflowRunRepository.findById(wfRun.getId()).orElseThrow();
    assertEquals(RunStatus.running, after.getStatus(), "the WorkflowRun must survive the retry");
    assertEquals(RunPhase.running, after.getPhase());
  }

  // The full retry sequence: park -> an agent is told to terminate the dead attempt's pod -> the
  // SAME TaskRun (one record, Tekton's model) becomes claimable again once its backoff elapses.
  @Test
  void aParkedRetryIsTerminatedByAnAgentThenRunsAgain() {
    String agent =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "retry-terminator", "retry-terminator.local", List.of("template")));
    WorkflowRunEntity wfRun =
        savedWorkflowRun("retry-cycle-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "retry-cycle",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    taskRun.setTimeout(30L);
    taskRunRepository.save(taskRun);
    taskRunService.tryClaim(taskRun.getId(), "dispatcher-attempt-1");
    taskRunService.tryStartExecution(taskRun.getId(), new Date(), 30L);
    blowTheDeadline(taskRun.getId());

    watcher.reapTaskTimeouts();

    // Parked: not on the execution queue while attempt 1's pod may still be alive.
    assertFalse(
        claimableIds().contains(taskRun.getId()),
        "a node awaiting termination of its previous pod must not be re-claimed for execution");

    // The agent is told to terminate it, in the shape its terminate handler keys off.
    TaskRun dispatched =
        dispatcherService.getTaskQueue(agent).getBody().stream()
            .filter(t -> taskRun.getId().equals(t.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the parked retry must be dispatched to termina"
                + "te attempt 1's pod"));
    assertEquals(RunPhase.completed, dispatched.getPhase());
    assertEquals(RunStatus.timedout, dispatched.getStatus());

    // The stored record is untouched by that wire shape: still one non-terminal TaskRun, now
    // released and waiting out its backoff.
    TaskRunEntity released = taskRunRepository.findById(taskRun.getId()).orElseThrow();
    assertEquals(RunStatus.ready, released.getStatus());
    assertEquals(RunPhase.pending, released.getPhase());
    assertNull(released.getClaim().getBy(), "the termination claim releases ownership");
    assertEquals(1, released.getRetry().getCount());
    assertNotNull(released.getRetry().getAfter(), "the backoff must still gate re-admission");
    assertFalse(claimableIds().contains(taskRun.getId()), "the backoff is served after the kill");

    // Backoff elapses: the SAME TaskRun runs again as attempt 2.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRun.getId())),
        new Update().set("retry.after", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);
    assertTrue(claimableIds().contains(taskRun.getId()));
    assertNotNull(
        taskRunService.tryClaim(taskRun.getId(), "dispatcher-attempt-2"),
        "the retried node must be claimable again");
    assertEquals(
        1,
        taskRunRepository.findAllById(List.of(taskRun.getId())).size(),
        "one record per node - no second TaskRun is created for the retry");
  }

  private void blowTheDeadline(String taskRunId) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("timeoutAt", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);
  }

  private List<String> claimableIds() {
    return taskRunService.findClaimable(List.of(TaskType.template), 50).stream()
        .map(TaskRunEntity::getId)
        .toList();
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
