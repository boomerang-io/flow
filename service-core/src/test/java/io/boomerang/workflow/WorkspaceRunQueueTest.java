package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowConcurrency;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.engine.WorkflowWatcher;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

/**
 * A workspace's concurrent-run quota bounds its runs in flight at admission and queues the rest:
 * a submit over it is created, parked {@code waiting/pending} and admitted oldest first when a run
 * ends. One queue per workspace holds the runs its own limit parks and the ones a workflow's limit
 * parks; a parked run holds no slot. The queue has a cap of its own, the one thing here that
 * refuses a submit.
 */
class WorkspaceRunQueueTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";
  private static final String TASK_SLUG = "run-queue-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private QuotaService quotaService;
  @Autowired private WorkflowWatcher workflowWatcher;
  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;
  @Autowired private ConfigurableApplicationContext applicationContext;
  @Autowired private MongoTemplate mongoTemplate;

  // Every run a test submitted, ended afterwards so none is left running or parked for the
  // classes that follow.
  private final List<String> submitted = new CopyOnWriteArrayList<>();

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    cancelRunsEarlierTestsLeftParked();
    setFeatureSetting(QUOTA_FEATURE, true);
  }

  @AfterEach
  void endSubmittedRuns() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
    // Parked runs first, so ending a running one admits nothing behind it.
    List<String> runningLast = new ArrayList<>(submitted);
    runningLast.sort((a, b) -> Boolean.compare(isParked(b), isParked(a)));
    for (String id : runningLast) {
      try {
        workflowRunService.cancel(id);
      } catch (RuntimeException ignored) {
        // Already terminal, or gone.
      }
    }
    submitted.clear();
  }

  private boolean isParked(String id) {
    return workflowRunRepository
        .findById(id)
        .map(r -> RunStatus.waiting.equals(r.getStatus()))
        .orElse(false);
  }

  private String workspace(String name, int concurrent, Integer queued) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(concurrent);
    quotas.setMaxQueuedRuns(queued);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private void createWorkflow(String workspace, String name, Integer limit) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    if (limit != null) {
      WorkflowConcurrency concurrency = new WorkflowConcurrency();
      concurrency.setLimit(limit);
      workflow.setConcurrency(concurrency);
    }
    workflowService.create(workspace, workflow);
  }

  private WorkflowRun submit(String workspace, String workflow, boolean start) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(workspace, workflow, request, start);
    submitted.add(run.getId());
    return run;
  }

  private WorkflowRunEntity run(String id) {
    return workflowRunRepository.findById(id).orElseThrow();
  }

  private void end(String id) {
    workflowRunService.cancel(id);
    awaitEngine("the run to complete")
        .untilAsserted(() -> assertEquals(RunPhase.completed, run(id).getPhase()));
  }

  private void awaitRunning(String id) {
    awaitEngine("the queued run to be admitted and started")
        .untilAsserted(() -> assertEquals(RunStatus.running, run(id).getStatus()));
  }

  private void assertParked(String id, String message) {
    WorkflowRunEntity parked = run(id);
    assertEquals(RunStatus.waiting, parked.getStatus());
    assertEquals(RunPhase.pending, parked.getPhase());
    assertEquals(message, parked.getStatusMessage());
  }

  private static final String WORKSPACE_FULL_OF_ONE =
      "Waiting for a slot: the workspace's limit of 1 concurrent run is reached.";

  @Test
  void aSubmitOverTheConcurrentQuotaWaitsAndStartsWhenARunEndsOldestFirst() {
    String workspace = workspace("run-queue-fifo", 1, null);
    createWorkflow(workspace, "run-queue-fifo-workflow", null);

    WorkflowRun first = submit(workspace, "run-queue-fifo-workflow", true);
    WorkflowRun second = submit(workspace, "run-queue-fifo-workflow", true);
    WorkflowRun third = submit(workspace, "run-queue-fifo-workflow", true);

    awaitRunning(first.getId());
    assertEquals(RunStatus.waiting, second.getStatus(), "the submit answers with the run as it stands");
    assertEquals(RunPhase.pending, second.getPhase());
    assertEquals(WORKSPACE_FULL_OF_ONE, second.getStatusMessage());
    assertParked(second.getId(), WORKSPACE_FULL_OF_ONE);
    assertParked(third.getId(), WORKSPACE_FULL_OF_ONE);
    assertNull(run(second.getId()).getTimeoutAt(), "time queued is not the run's");
    CurrentQuotas usage = quotaService.current(workspace);
    assertEquals(1, usage.getCurrentConcurrentRuns(), "concurrent counts the runs holding a slot");
    assertEquals(2, usage.getCurrentQueuedRuns());

    end(first.getId());

    awaitRunning(second.getId());
    assertParked(third.getId(), WORKSPACE_FULL_OF_ONE);

    end(second.getId());

    awaitRunning(third.getId());
  }

  @Test
  void aStagedRunPassesTheGateLikeAnyRunAndIsStartedWhenAdmitted() {
    String workspace = workspace("run-queue-staged", 1, null);
    createWorkflow(workspace, "run-queue-staged-workflow", null);

    WorkflowRun staged = submit(workspace, "run-queue-staged-workflow", false);
    WorkflowRun parked = submit(workspace, "run-queue-staged-workflow", false);

    assertEquals(RunStatus.ready, run(staged.getId()).getStatus(), "admitted, and holding the slot");
    assertParked(parked.getId(), WORKSPACE_FULL_OF_ONE);

    end(staged.getId());

    awaitRunning(parked.getId());
  }

  @Test
  void aRunParkedOnItsWorkflowLimitHoldsNoWorkspaceSlot() {
    String workspace = workspace("run-queue-two-limits", 2, null);
    createWorkflow(workspace, "run-queue-limited-workflow", 1);
    createWorkflow(workspace, "run-queue-open-workflow", null);

    WorkflowRun limitedFirst = submit(workspace, "run-queue-limited-workflow", true);
    WorkflowRun limitedSecond = submit(workspace, "run-queue-limited-workflow", true);
    WorkflowRun open = submit(workspace, "run-queue-open-workflow", true);
    WorkflowRun overTheWorkspace = submit(workspace, "run-queue-open-workflow", true);

    awaitRunning(limitedFirst.getId());
    assertParked(
        limitedSecond.getId(),
        "Waiting for a slot: the workflow's concurrency limit of 1 in-flight run is reached.");
    // The run its workflow parked took no slot of the workspace's two.
    awaitRunning(open.getId());
    assertParked(
        overTheWorkspace.getId(),
        "Waiting for a slot: the workspace's limit of 2 concurrent runs is reached.");
    CurrentQuotas usage = quotaService.current(workspace);
    assertEquals(2, usage.getCurrentConcurrentRuns());
    // Both wait in the one queue; the workspace's cap counts only the run its own limit holds.
    assertEquals(StatusReason.CONCURRENCY_LIMIT, run(limitedSecond.getId()).getStatusReason());
    assertEquals(StatusReason.WORKSPACE_CONCURRENCY_LIMIT, run(overTheWorkspace.getId()).getStatusReason());
    assertEquals(1, usage.getCurrentQueuedRuns(), "only the run waiting for a workspace slot");
  }

  @Test
  void aChildRunIsNeverQueuedAndCountsTowardTheTotal() {
    String workspace = workspace("run-queue-child", 1, null);
    createWorkflow(workspace, "run-queue-child-workflow", null);
    WorkflowRun parent = submit(workspace, "run-queue-child-workflow", true);
    awaitRunning(parent.getId());

    // The submit a runworkflow task makes: by workflow id, with the task trigger.
    WorkflowSubmitRequest childRequest = new WorkflowSubmitRequest();
    childRequest.setTrigger(TriggerEnum.task);
    WorkflowRun child = workflowService.submit(parent.getWorkflowRef(), childRequest, true);
    submitted.add(child.getId());

    // A child starts with the workspace full.
    awaitRunning(child.getId());
    assertEquals(2, quotaService.current(workspace).getCurrentConcurrentRuns());

    end(parent.getId());
    WorkflowRun next = submit(workspace, "run-queue-child-workflow", true);

    assertParked(next.getId(), WORKSPACE_FULL_OF_ONE);
  }

  // The submit a runworkflow task makes: by workflow id, with the task trigger.
  private WorkflowRun submitChild(String workflowRef) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.task);
    WorkflowRun child = workflowService.submit(workflowRef, request, true);
    submitted.add(child.getId());
    return child;
  }

  private String workflowRef(String workspace, String workflow) {
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.of(List.of(workflow)))
        .get(0);
  }

  @Test
  void aChildIsNeitherQueuedNorRefusedWithEverySlotAndTheQueueFull() {
    String workspace = workspace("run-queue-child-full", 1, 1);
    createWorkflow(workspace, "run-queue-child-full-workflow", null);
    WorkflowRun parent = submit(workspace, "run-queue-child-full-workflow", true);
    awaitRunning(parent.getId());
    WorkflowRun queued = submit(workspace, "run-queue-child-full-workflow", true);
    assertParked(queued.getId(), WORKSPACE_FULL_OF_ONE);

    WorkflowRun child = submitChild(parent.getWorkflowRef());

    awaitRunning(child.getId());
    assertParked(queued.getId(), WORKSPACE_FULL_OF_ONE);
  }

  // A parent holds the workspace's one slot and waits on its child. The child's workflow runs one
  // at a time, so a second child parks; when the first ends the second must run although the
  // workspace is full, or the parent would wait on it for ever. A run that is not a child stays
  // parked: the workspace's limit holds it.
  @Test
  void aChildParkedOnItsWorkflowsLimitIsAdmittedWhileTheWorkspaceIsFull() {
    String workspace = workspace("run-queue-child-parked", 1, null);
    createWorkflow(workspace, "run-queue-child-parked-parent", null);
    createWorkflow(workspace, "run-queue-child-parked-child", 1);
    String childWorkflow = workflowRef(workspace, "run-queue-child-parked-child");
    WorkflowRun parent = submit(workspace, "run-queue-child-parked-parent", true);
    awaitRunning(parent.getId());
    WorkflowRun notAChild = submit(workspace, "run-queue-child-parked-child", true);
    assertParked(notAChild.getId(), WORKSPACE_FULL_OF_ONE);
    WorkflowRun first = submitChild(childWorkflow);
    awaitRunning(first.getId());
    WorkflowRun second = submitChild(childWorkflow);
    assertParked(
        second.getId(),
        "Waiting for a slot: the workflow's concurrency limit of 1 in-flight run is reached.");

    end(first.getId());

    awaitRunning(second.getId());
    assertEquals(RunStatus.running, run(parent.getId()).getStatus());
    assertParked(notAChild.getId(), WORKSPACE_FULL_OF_ONE);
  }

  @Test
  void theSweepAdmitsAParkedChildWhileTheWorkspaceIsFull() {
    String workspace = workspace("run-queue-child-sweep", 1, null);
    createWorkflow(workspace, "run-queue-child-sweep-parent", null);
    createWorkflow(workspace, "run-queue-child-sweep-child", 1);
    String childWorkflow = workflowRef(workspace, "run-queue-child-sweep-child");
    WorkflowRun parent = submit(workspace, "run-queue-child-sweep-parent", true);
    awaitRunning(parent.getId());
    WorkflowRun notAChild = submit(workspace, "run-queue-child-sweep-parent", true);
    WorkflowRun first = submitChild(childWorkflow);
    awaitRunning(first.getId());
    WorkflowRun second = submitChild(childWorkflow);
    assertEquals(RunStatus.waiting, run(second.getId()).getStatus());
    // The first child ends with no transition published: its completion's drain was lost.
    endSilently(first.getId());

    workflowWatcher.readmitParkedRuns();

    awaitRunning(second.getId());
    assertParked(notAChild.getId(), WORKSPACE_FULL_OF_ONE);
  }

  // The default limit of four, every slot held by a parent whose child's workflow runs one at a
  // time: each parent gets its child in turn.
  @Test
  void fourParentsHoldingEverySlotEachGetTheirParkedChild() {
    String workspace = workspace("run-queue-child-four", 4, null);
    createWorkflow(workspace, "run-queue-child-four-parent", null);
    createWorkflow(workspace, "run-queue-child-four-child", 1);
    String childWorkflow = workflowRef(workspace, "run-queue-child-four-child");
    for (int i = 0; i < 4; i++) {
      awaitRunning(submit(workspace, "run-queue-child-four-parent", true).getId());
    }
    List<WorkflowRun> children = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      children.add(submitChild(childWorkflow));
    }
    awaitRunning(children.get(0).getId());
    assertEquals(3, stillParked(children.stream().map(WorkflowRun::getId).toList()));

    for (int i = 0; i < 3; i++) {
      end(children.get(i).getId());
      awaitRunning(children.get(i + 1).getId());
      assertEquals(
          2 - i,
          stillParked(children.stream().map(WorkflowRun::getId).toList()),
          "one child of the workflow at a time");
    }
  }

  // The oldest parked run cannot be re-admitted: its admission throws, every time. The run behind
  // it takes the slot, on the completion's drain and on the sweep alike.
  @Test
  void aParkedRunWhoseReadmissionFailsDoesNotHoldBackTheRunBehindIt() {
    String workspace = workspace("run-queue-failing", 1, null);
    createWorkflow(workspace, "run-queue-failing-workflow", null);
    WorkflowRun holder = submit(workspace, "run-queue-failing-workflow", true);
    awaitRunning(holder.getId());
    WorkflowRun failing = submit(workspace, "run-queue-failing-workflow", true);
    WorkflowRun healthy = submit(workspace, "run-queue-failing-workflow", true);
    WorkflowRun behind = submit(workspace, "run-queue-failing-workflow", true);
    // No revision ref: reading the revision throws before anything is decided.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(failing.getId())),
        new Update().unset("workflowRevisionRef").set("creationDate", new Date(System.currentTimeMillis() - 60000)),
        WorkflowRunEntity.class);

    end(holder.getId());

    awaitRunning(healthy.getId());
    assertEquals(RunStatus.waiting, run(failing.getId()).getStatus(), "left parked, to be tried again");
    assertEquals(RunStatus.waiting, run(behind.getId()).getStatus());
    // The same on the sweep, with the failing run still the oldest.
    endSilently(healthy.getId());
    workflowWatcher.readmitParkedRuns();
    awaitRunning(behind.getId());
    endSilently(failing.getId());
  }

  private static final String WORKFLOW_FULL_OF_ONE =
      "Waiting for a slot: the workflow's concurrency limit of 1 in-flight run is reached.";

  // The queue's cap holds the runs waiting for a slot of the workspace. A workflow's own queue is
  // unbounded: its runs never count toward the cap, and the cap never refuses one of them, so one
  // workflow's backlog cannot make the workspace refuse another workflow's submit.
  @Test
  void aWorkflowsOwnBacklogNeitherFillsNorIsRefusedByTheWorkspaceQueue() {
    String workspace = workspace("run-queue-own-backlog", 2, 2);
    createWorkflow(workspace, "run-queue-own-backlog-limited", 1);
    createWorkflow(workspace, "run-queue-own-backlog-other", null);
    WorkflowRun first = submit(workspace, "run-queue-own-backlog-limited", true);
    awaitRunning(first.getId());
    List<String> backlog = new ArrayList<>();
    for (int i = 0; i < 29; i++) {
      WorkflowRun run = submit(workspace, "run-queue-own-backlog-limited", true);
      assertEquals(StatusReason.CONCURRENCY_LIMIT, run.getStatusReason(), "held by its own workflow");
      backlog.add(run.getId());
    }
    assertEquals(29, stillParked(backlog), "all 29 wait: a workflow's queue is unbounded");
    assertEquals(0, quotaService.current(workspace).getCurrentQueuedRuns(), "none waits for the workspace");

    WorkflowRun other = submit(workspace, "run-queue-own-backlog-other", true);
    awaitRunning(other.getId());
    WorkflowRun queuedFirst = submit(workspace, "run-queue-own-backlog-other", true);
    WorkflowRun queuedSecond = submit(workspace, "run-queue-own-backlog-other", true);
    WorkflowRunEntity held = run(queuedFirst.getId());
    assertParked(queuedFirst.getId(), "Waiting for a slot: the workspace's limit of 2 concurrent runs is reached.");
    assertEquals(StatusReason.WORKSPACE_CONCURRENCY_LIMIT, held.getStatusReason());
    assertEquals(StatusReason.WORKSPACE_CONCURRENCY_LIMIT, queuedFirst.getStatusReason(), "on the answer too");
    assertEquals(RunStatus.waiting, run(queuedSecond.getId()).getStatus());
    assertEquals(2, quotaService.current(workspace).getCurrentQueuedRuns());

    BoomerangException refused =
        assertThrows(
            BoomerangException.class, () -> submit(workspace, "run-queue-own-backlog-other", true));
    assertEquals("QUOTA_EXCEEDED", refused.getReason(), "the third run held by the workspace");
    assertEquals("Queued runs", refused.getArgs()[0]);
    // With every slot taken and the workspace's queue full, the limited workflow still queues.
    WorkflowRun stillQueues = submit(workspace, "run-queue-own-backlog-limited", true);
    assertParked(stillQueues.getId(), WORKFLOW_FULL_OF_ONE);
  }

  // A parked run carries the limit that holds it as its reason, rewritten whenever another limit
  // takes over and cleared when it is admitted; a cancel of a parked run leaves no reason behind.
  @Test
  void aParkedRunTakesTheReasonOfTheLimitThatHoldsItNow() {
    String workspace = workspace("run-queue-reason", 2, null);
    createWorkflow(workspace, "run-queue-reason-holder", null);
    createWorkflow(workspace, "run-queue-reason-limited", 1);
    String limited = workflowRef(workspace, "run-queue-reason-limited");
    WorkflowRun holder = submit(workspace, "run-queue-reason-holder", true);
    WorkflowRun otherHolder = submit(workspace, "run-queue-reason-holder", true);
    awaitRunning(holder.getId());
    awaitRunning(otherHolder.getId());
    WorkflowRun parked = submit(workspace, "run-queue-reason-limited", true);
    assertEquals(StatusReason.WORKSPACE_CONCURRENCY_LIMIT, run(parked.getId()).getStatusReason());
    assertEquals(1, quotaService.current(workspace).getCurrentQueuedRuns());
    // A child fills the limited workflow's one slot: the workspace's limit never holds a child,
    // though the child counts toward its total.
    WorkflowRun child = submitChild(limited);
    awaitRunning(child.getId());

    end(holder.getId());
    assertEquals(StatusReason.WORKSPACE_CONCURRENCY_LIMIT, run(parked.getId()).getStatusReason(), "still full");
    end(otherHolder.getId());

    assertParked(parked.getId(), WORKFLOW_FULL_OF_ONE);
    assertEquals(StatusReason.CONCURRENCY_LIMIT, run(parked.getId()).getStatusReason());
    assertEquals(0, quotaService.current(workspace).getCurrentQueuedRuns(), "its workflow holds it now");

    end(child.getId());

    awaitRunning(parked.getId());
    assertNull(run(parked.getId()).getStatusReason(), "admission clears the reason");

    WorkflowRun cancelled = submit(workspace, "run-queue-reason-limited", true);
    assertEquals(StatusReason.CONCURRENCY_LIMIT, run(cancelled.getId()).getStatusReason());
    workflowRunService.cancel(cancelled.getId());
    WorkflowRunEntity after = run(cancelled.getId());
    assertEquals(RunStatus.cancelled, after.getStatus());
    assertNull(after.getStatusReason(), "a cancel, not the limit, ended it");
  }

  // The instance that admitted a run with nothing to provision stopped before it started it. Two
  // sweeps in a row find it still waiting and start it; a run with a workspace is left to its
  // dispatcher.
  @Test
  void anAdmittedRunWhoseStartWasLostIsStartedByTheSweep() {
    setFeatureSetting(QUOTA_FEATURE, false);
    String workspace = workspace("run-queue-lost-start", 0, null);
    createWorkflow(workspace, "run-queue-lost-start-workflow", null);
    Workflow withVolume = runnableWorkflow("run-queue-lost-start-volume", TASK_SLUG);
    WorkflowWorkspace volume = new WorkflowWorkspace();
    volume.setSize("1Gi");
    withVolume.setWorkspace(volume);
    workflowService.create(workspace, withVolume);
    // Admitted and not started: the state the lost start leaves.
    WorkflowRun lost = submit(workspace, "run-queue-lost-start-workflow", false);
    WorkflowRun provisioned = submit(workspace, "run-queue-lost-start-volume", false);
    assertEquals(RunStatus.ready, run(lost.getId()).getStatus());

    workflowWatcher.startAdmittedRuns();
    // One sighting is a start on its way: nothing is started, now or a second later.
    Awaitility.await("the run to stay unstarted after one sighting")
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(3))
        .until(() -> RunStatus.ready.equals(run(lost.getId()).getStatus()));

    workflowWatcher.startAdmittedRuns();

    awaitRunning(lost.getId());
    assertEquals(RunStatus.ready, run(provisioned.getId()).getStatus(), "the dispatcher's to start");
    assertEquals(RunPhase.pending, run(provisioned.getId()).getPhase());
  }

  @Test
  void aFullQueueRefusesTheSubmitAndZeroIsUnlimited() {
    String workspace = workspace("run-queue-cap", 1, 2);
    createWorkflow(workspace, "run-queue-cap-workflow", null);
    WorkflowRun running = submit(workspace, "run-queue-cap-workflow", true);
    submit(workspace, "run-queue-cap-workflow", true);
    submit(workspace, "run-queue-cap-workflow", true);

    BoomerangException refused =
        assertThrows(
            BoomerangException.class, () -> submit(workspace, "run-queue-cap-workflow", true));

    assertEquals("QUOTA_EXCEEDED", refused.getReason());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused.getStatus());
    assertEquals("Queued runs", refused.getArgs()[0]);
    assertEquals(3, runsOf(running.getWorkflowRef()), "the refused submit left no run behind");

    Quotas unlimited = new Quotas();
    unlimited.setMaxQueuedRuns(0);
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setQuotas(unlimited);
    workspaceService.patch(workspace, patch);

    WorkflowRun queued = submit(workspace, "run-queue-cap-workflow", true);

    assertParked(queued.getId(), WORKSPACE_FULL_OF_ONE);
    assertEquals(3, quotaService.current(workspace).getCurrentQueuedRuns());
  }

  // The queue is full only while every slot is taken, and only with runs waiting for a workspace
  // slot: the runs another workflow's own limit has parked take none of its places.
  @Test
  void aRunWithASlotFreeIsNotRefusedByAQueueAnotherWorkflowFilled() {
    String workspace = workspace("run-queue-free-slot", 2, 1);
    createWorkflow(workspace, "run-queue-free-slot-limited", 1);
    createWorkflow(workspace, "run-queue-free-slot-open", null);
    WorkflowRun limited = submit(workspace, "run-queue-free-slot-limited", true);
    WorkflowRun parkedByItsWorkflow = submit(workspace, "run-queue-free-slot-limited", true);
    awaitRunning(limited.getId());
    assertEquals(RunStatus.waiting, run(parkedByItsWorkflow.getId()).getStatus());
    assertEquals(0, quotaService.current(workspace).getCurrentQueuedRuns(), "its workflow holds it");

    WorkflowRun open = submit(workspace, "run-queue-free-slot-open", true);

    awaitRunning(open.getId());
    WorkflowRun queued = submit(workspace, "run-queue-free-slot-open", true);
    assertParked(queued.getId(), "Waiting for a slot: the workspace's limit of 2 concurrent runs is reached.");
    BoomerangException refused =
        assertThrows(
            BoomerangException.class, () -> submit(workspace, "run-queue-free-slot-open", true));
    assertEquals("QUOTA_EXCEEDED", refused.getReason(), "every slot taken and the queue full");
    assertEquals("Queued runs", refused.getArgs()[0]);
  }

  @Test
  void aRunBlockedByItsWorkflowDoesNotHideAnotherWorkflowsQueuedRun() {
    String workspace = workspace("run-queue-blocked", 2, null);
    createWorkflow(workspace, "run-queue-blocked-workflow", 1);
    createWorkflow(workspace, "run-queue-other-workflow", null);
    WorkflowRun blocking = submit(workspace, "run-queue-blocked-workflow", true);
    WorkflowRun other = submit(workspace, "run-queue-other-workflow", true);
    // The two oldest parked runs are held by their own workflow's limit; the third only by the
    // workspace's.
    WorkflowRun blockedFirst = submit(workspace, "run-queue-blocked-workflow", true);
    WorkflowRun blockedSecond = submit(workspace, "run-queue-blocked-workflow", true);
    WorkflowRun behind = submit(workspace, "run-queue-other-workflow", true);
    awaitRunning(blocking.getId());
    assertEquals(RunStatus.waiting, run(behind.getId()).getStatus());

    end(other.getId());

    awaitRunning(behind.getId());
    assertEquals(RunStatus.waiting, run(blockedFirst.getId()).getStatus());
    assertEquals(RunStatus.waiting, run(blockedSecond.getId()).getStatus());
  }

  // Two runs admitted at the same moment each pass the first count. The recount after admission
  // keeps the oldest up to the limit, so the younger one steps back into the queue. The older
  // run is put in flight here between the younger one's admission write and its recount, which
  // is when the admission's own event is published.
  @Test
  void aRunAdmittedTogetherWithAnOlderOneStepsBackIntoTheQueue() {
    String workspace = workspace("run-queue-recount", 1, null);
    // The workflow declares a workspace, so its admission stamps a deadline.
    Workflow withVolume = runnableWorkflow("run-queue-recount-workflow", TASK_SLUG);
    WorkflowWorkspace volume = new WorkflowWorkspace();
    volume.setSize("1Gi");
    withVolume.setWorkspace(volume);
    workflowService.create(workspace, withVolume);
    String workflowRef = workflowRef(workspace, "run-queue-recount-workflow");
    List<String> older = new CopyOnWriteArrayList<>();
    ApplicationListener<PayloadApplicationEvent<?>> admittedTogether =
        new ApplicationListener<>() {
          @Override
          public void onApplicationEvent(PayloadApplicationEvent<?> event) {
            if (event.getPayload() instanceof WorkflowRunTransition transition
                && workflowRef.equals(transition.workflowRef())
                && RunStatus.ready == transition.toStatus()
                && older.isEmpty()) {
              WorkflowRunEntity run =
                  savedWorkflowRun(workflowRef, RunStatus.ready, RunPhase.pending);
              run.setCreationDate(new Date(System.currentTimeMillis() - 60000));
              older.add(workflowRunRepository.save(run).getId());
            }
          }
        };
    applicationContext.addApplicationListener(admittedTogether);
    try {
      WorkflowRun younger = submit(workspace, "run-queue-recount-workflow", false);

      assertEquals(1, older.size(), "the older run went in flight during the admission");
      assertParked(younger.getId(), WORKSPACE_FULL_OF_ONE);
      assertNull(
          run(younger.getId()).getTimeoutAt(),
          "the deadline its admission stamped for the wait for a dispatcher goes when it steps back");
      assertEquals(RunStatus.waiting, younger.getStatus(), "the submit answers with the parked run");
      assertEquals(RunStatus.ready, run(older.get(0)).getStatus());
    } finally {
      applicationContext.removeApplicationListener(admittedTogether);
      older.forEach(
          id -> {
            WorkflowRunEntity run = run(id);
            run.setStatus(RunStatus.cancelled);
            run.setPhase(RunPhase.completed);
            workflowRunRepository.save(run);
          });
    }
  }

  @Test
  void theSweepReachesEveryWorkflowWithAParkedRun() {
    // No workspace limit applies, so each workflow's queue is its own: 120 queues, 50 a tick.
    setFeatureSetting(QUOTA_FEATURE, false);
    String workspace = workspace("run-queue-sweep", 0, null);
    List<String> parked = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      String workflow = "run-queue-sweep-workflow-" + i;
      createWorkflow(workspace, workflow, null);
      WorkflowRun run = submit(workspace, workflow, false);
      workflowRunStateHelper.tryPark(
          run.getId(), StatusReason.CONCURRENCY_LIMIT, "Waiting for a slot: test");
      parked.add(run.getId());
    }
    assertEquals(120, stillParked(parked));

    workflowWatcher.readmitParkedRuns();
    assertEquals(70, stillParked(parked), "a tick visits a page of 50 workflows");
    workflowWatcher.readmitParkedRuns();
    assertEquals(20, stillParked(parked), "the next tick takes the page after it");
    workflowWatcher.readmitParkedRuns();
    assertEquals(0, stillParked(parked), "three ticks reach all 120");
  }

  @Test
  void switchingQuotasOffAdmitsTheQueue() {
    String workspace = workspace("run-queue-switched-off", 1, null);
    createWorkflow(workspace, "run-queue-switched-off-workflow", null);
    submit(workspace, "run-queue-switched-off-workflow", true);
    WorkflowRun second = submit(workspace, "run-queue-switched-off-workflow", true);
    WorkflowRun third = submit(workspace, "run-queue-switched-off-workflow", true);
    assertEquals(RunStatus.waiting, run(third.getId()).getStatus());

    setFeatureSetting(QUOTA_FEATURE, false);
    workflowWatcher.readmitParkedRuns();

    awaitRunning(second.getId());
    awaitRunning(third.getId());
  }

  @Test
  void aLoweredQuotaAdmitsNothingUntilTheRunsInFlightAreBelowIt() {
    String workspace = workspace("run-queue-lowered", 3, null);
    createWorkflow(workspace, "run-queue-lowered-workflow", null);
    WorkflowRun first = submit(workspace, "run-queue-lowered-workflow", true);
    WorkflowRun second = submit(workspace, "run-queue-lowered-workflow", true);
    WorkflowRun third = submit(workspace, "run-queue-lowered-workflow", true);
    WorkflowRun queued = submit(workspace, "run-queue-lowered-workflow", true);
    assertEquals(RunStatus.waiting, run(queued.getId()).getStatus());

    Quotas lowered = new Quotas();
    lowered.setMaxConcurrentRuns(1);
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setQuotas(lowered);
    workspaceService.patch(workspace, patch);

    end(first.getId());
    workflowWatcher.readmitParkedRuns();
    assertEquals(RunStatus.waiting, run(queued.getId()).getStatus(), "two still run, over a limit of 1");
    end(second.getId());
    workflowWatcher.readmitParkedRuns();
    assertEquals(RunStatus.waiting, run(queued.getId()).getStatus(), "one still runs, at the limit");

    end(third.getId());

    awaitRunning(queued.getId());
  }

  // The run ends with no transition published: a completion whose drain was lost with its instance.
  private void endSilently(String id) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("status", RunStatus.cancelled).set("phase", RunPhase.completed),
        WorkflowRunEntity.class);
  }

  private long stillParked(List<String> ids) {
    return workflowRunRepository.findAllById(ids).stream()
        .filter(r -> RunStatus.waiting.equals(r.getStatus()))
        .count();
  }

  private long runsOf(String workflowRef) {
    return workflowRunRepository
        .findByWorkflowRefAndPhaseIn(workflowRef, List.of(RunPhase.values()))
        .size();
  }
}
