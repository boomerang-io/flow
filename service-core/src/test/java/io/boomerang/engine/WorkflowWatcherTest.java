package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The watcher's self-healing sweeps: durable timeout reaping with retry backoff (which is also
 * the crash recovery for a killed claimant - one sweep pass recovers the task), stalled-run
 * recovery, and the wind-down of deleted Workflows. Every sweep action
 * goes through a Compare-And-Set, so the sweeps are exercised by direct invocation - one call is
 * one tick.
 */
class WorkflowWatcherTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowWatcher watcher;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private ActionRepository actionRepository;
  @Autowired private WorkflowScheduleRepository scheduleRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private WorkflowService workflowService;

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
    // The killed claimant may have left a pod behind, so the node parks awaiting its termination
    // rather than going straight back on the execution queue. Non-terminal, so the DAG sees
    // nothing finish.
    assertEquals(RunStatus.waiting, requeued.getStatus());
    assertEquals(RunPhase.pending, requeued.getPhase());
    assertEquals(
        "dead-agent",
        requeued.getClaim().getBy(),
        "requeue keeps the claim as the marker that a pod may still be out there");
    assertEquals(2L, requeued.getClaim().getSeq(), "the requeue supersedes the reaped claimant");
    assertNull(requeued.getTimeoutAt(), "the deadline is baked at the next execution start");
    assertNotNull(requeued.getRetry());
    assertEquals(1, requeued.getRetry().getCount());
    assertTrue(
        requeued.getRetry().getAfter().after(new Date()),
        "the retry backoff must gate the next attempt");

    // Not re-claimable for execution while the previous pod may still be alive.
    assertFalse(claimPageContains(taskRunId));

    // An agent takes the termination, which releases the claim and re-arms the node.
    assertNotNull(taskRunService.tryClaimForTermination(taskRunId, "live-agent"));
    TaskRunEntity released = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunStatus.ready, released.getStatus());
    assertNull(released.getClaim().getBy(), "the termination release clears the claim ownership");
    assertEquals(2L, released.getClaim().getSeq(), "claim.seq is never cleared");

    // The claim page still honours the backoff: excluded until retry.after elapses.
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
    assertEquals(RunStatus.waiting, requeued.getStatus());
    assertEquals(2, requeued.getRetry().getCount());
    assertEquals(4L, requeued.getClaim().getSeq(), "each requeue supersedes the claim it reaped");
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

    // One sighting is what a run between a task's end and the next admission looks like: no
    // advance until a later sweep finds the run empty again.
    watcher.recoverStalledRuns();
    awaitEngine("no advance on a single sighting")
        .during(Duration.ofSeconds(2))
        .until(
            () ->
                RunPhase.pending.equals(
                    taskRunRepository.findById(end.getId()).orElseThrow().getPhase()));

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

  // A run with a workspace waits ready/pending for a dispatcher to provision it. With none, its
  // own budget ends the wait, so it does not hold a concurrent-run slot for ever.
  @Test
  void unstartedRunWithAWorkspacePastDeadlineIsTimedOut() {
    Task template = new Task();
    template.setName("wf-deadline-unstarted-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    Workflow workflow =
        runnableWorkflow("wf-deadline-unstarted", taskService.create(template).getId());
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("1Gi");
    workflow.setWorkspace(workspace);
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTimeout(1L);

    String wfRunId = workflowService.submit(workflowId, request, true).getId();

    WorkflowRunEntity parked = workflowRunRepository.findById(wfRunId).orElseThrow();
    assertEquals(RunStatus.ready, parked.getStatus());
    assertEquals(RunPhase.pending, parked.getPhase());
    assertNotNull(
        parked.getTimeoutAt(), "the deadline runs from submit while no dispatcher claims");
    // Two minutes waiting on a one-minute budget.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(wfRunId)),
        new Update().set("timeoutAt", new Date(System.currentTimeMillis() - 60_000)),
        WorkflowRunEntity.class);

    watcher.reapWorkflowTimeouts();

    awaitEngine("the unclaimed run past its deadline to be timed out")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.timedout, after.getStatus());
              assertEquals(RunPhase.completed, after.getPhase());
            });
    WorkflowRunEntity after = workflowRunRepository.findById(wfRunId).orElseThrow();
    assertNull(after.getTimeoutAt());
    assertTrue(after.getClaim() == null || after.getClaim().getBy() == null);
    assertTrue(
        taskRunRepository.findByWorkflowRunRef(wfRunId).stream()
            .noneMatch(t -> RunPhase.running.equals(t.getPhase())),
        "a run that never started leaves no task run in flight");
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

  @Test
  void deletedWorkflowWithCompletedRunsIsPruned() {
    WorkflowEntity workflow = savedWorkflow("pruned-wf", WorkflowStatus.deleted);
    WorkflowRunEntity wfRun =
        savedWorkflowRun(workflow.getId(), RunStatus.cancelled, RunPhase.completed);
    String taskRunId =
        savedTaskRun(
                "pruned-task",
                TaskType.template,
                RunStatus.cancelled,
                RunPhase.completed,
                workflow.getId(),
                wfRun.getId())
            .getId();
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setWorkflowRef(workflow.getId());
    revision.setVersion(1);
    revision = workflowRevisionRepository.save(revision);
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflow.getId());
    action.setWorkflowRunRef(wfRun.getId());
    action.setStatus(ActionStatus.cancelled);
    action = actionRepository.save(action);
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setWorkflowRef(workflow.getId());
    schedule = scheduleRepository.save(schedule);
    relationshipService.createNode(
        RelationshipType.WORKFLOW, workflow.getId(), workflow.getId(), Optional.empty());
    AuditEventEntity audit = new AuditEventEntity();
    audit.setResourceType("workflow");
    audit.setResourceId(workflow.getId());
    audit = auditEventRepository.save(audit);

    watcher.pruneDeletedWorkflows();

    assertFalse(workflowRepository.existsById(workflow.getId()));
    assertFalse(workflowRunRepository.existsById(wfRun.getId()));
    assertFalse(taskRunRepository.existsById(taskRunId));
    assertFalse(workflowRevisionRepository.existsById(revision.getId()));
    assertFalse(actionRepository.existsById(action.getId()));
    assertFalse(scheduleRepository.existsById(schedule.getId()));
    assertFalse(
        relationshipService.doesSlugOrRefExistForType(
            RelationshipType.WORKFLOW, workflow.getId()));
    assertTrue(
        auditEventRepository.existsById(audit.getId()),
        "audit records are the record that outlives the deletion");
  }

  @Test
  void pruningADeletedWorkflowRemovesItsRunsRelationshipNodes() {
    WorkflowEntity workflow = savedWorkflow("pruned-nodes-wf", WorkflowStatus.deleted);
    WorkflowEntity other = savedWorkflow("kept-wf", WorkflowStatus.active);
    WorkflowRunEntity first =
        savedWorkflowRun(workflow.getId(), RunStatus.succeeded, RunPhase.completed);
    WorkflowRunEntity second =
        savedWorkflowRun(workflow.getId(), RunStatus.failed, RunPhase.completed);
    WorkflowRunEntity kept =
        savedWorkflowRun(other.getId(), RunStatus.succeeded, RunPhase.completed);
    for (WorkflowRunEntity run : List.of(first, second, kept)) {
      relationshipService.createNode(
          RelationshipType.WORKFLOWRUN, run.getId(), run.getId(), Optional.empty());
    }

    watcher.pruneDeletedWorkflows();

    assertFalse(workflowRunRepository.existsById(first.getId()));
    assertFalse(workflowRunRepository.existsById(second.getId()));
    assertFalse(
        relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKFLOWRUN, first.getId()));
    assertFalse(
        relationshipService.doesSlugOrRefExistForType(
            RelationshipType.WORKFLOWRUN, second.getId()));
    assertTrue(workflowRunRepository.existsById(kept.getId()));
    assertTrue(
        relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKFLOWRUN, kept.getId()));
  }

  @Test
  void deletedWorkflowWithInFlightRunIsNotPruned() {
    WorkflowEntity workflow = savedWorkflow("still-running-wf", WorkflowStatus.deleted);
    WorkflowRunEntity inFlight =
        savedWorkflowRun(workflow.getId(), RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "in-flight-task",
                TaskType.template,
                RunStatus.running,
                RunPhase.running,
                workflow.getId(),
                inFlight.getId())
            .getId();
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setWorkflowRef(workflow.getId());
    revision.setVersion(1);
    revision = workflowRevisionRepository.save(revision);

    watcher.pruneDeletedWorkflows();

    assertTrue(workflowRepository.existsById(workflow.getId()));
    assertTrue(workflowRunRepository.existsById(inFlight.getId()));
    assertTrue(taskRunRepository.existsById(taskRunId));
    assertTrue(workflowRevisionRepository.existsById(revision.getId()));
  }

  @Test
  void activeWorkflowIsNeverPruned() {
    WorkflowEntity workflow = savedWorkflow("active-wf", WorkflowStatus.active);
    WorkflowRunEntity run =
        savedWorkflowRun(workflow.getId(), RunStatus.succeeded, RunPhase.completed);

    watcher.pruneDeletedWorkflows();

    assertTrue(workflowRepository.existsById(workflow.getId()));
    assertTrue(workflowRunRepository.existsById(run.getId()));
  }

  private WorkflowEntity savedWorkflow(String name, WorkflowStatus status) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName(name);
    workflow.setStatus(status);
    return workflowRepository.save(workflow);
  }

  private void claimWithExpiredDeadline(String taskRunId, String claimedBy, long claimSeq) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("claim.seq", claimSeq)
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
