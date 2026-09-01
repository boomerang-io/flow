package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.util.Backoff;
import io.boomerang.common.util.SweepRunner;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Self-healing sweeps that run on every instance - no leader election. Each sweep pages a narrow
 * indexed query and acts only through the live path's Compare-And-Set primitives, so overlapping
 * sweeps (and sweeps overlapping the live path) are harmless. The per-instance startup jitter
 * de-phases the instances' schedules.
 */
@Service
public class WorkflowWatcher {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final int PAGE_SIZE = EngineConstants.SWEEP_PAGE_SIZE;

  // A run must have been started at least this long before it is checked for a lost advance,
  // so freshly-started runs whose first tasks are still being queued are not churned.
  private static final long STALL_GRACE_MILLIS = 60000;

  // Only agent-executed types are requeued on timeout - that is the crash recovery for a killed
  // claimant. Gates, waits and inline system tasks time out terminally, as they always have.
  private static final Set<TaskType> REQUEUEABLE_TYPES =
      EnumSet.of(TaskType.template, TaskType.custom, TaskType.script, TaskType.generic);

  private static final int MAX_RETRIES = 3;

  private static final List<RunPhase> IN_FLIGHT_PHASES =
      List.of(RunPhase.pending, RunPhase.queued, RunPhase.running);

  // A dispatcher is treated as gone once it has not connected for this long. Its queue poll
  // refreshes lastConnectedDate every 5s, so this is twelve missed cycles - short enough that a
  // dead dispatcher's claims are recovered promptly, long enough that a GC pause or network blip
  // does not reap a healthy one. Recovering a claim re-dispatches the task while the original
  // executor may still be running it, and nothing kills that executor, so this cannot be tightened
  // further until the dispatch protocol can cancel in-flight work.
  private static final long DISPATCHER_STALE_MILLIS = 60000;

  private final TaskRunService taskRunService;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRepository workflowRepository;
  private final WorkflowRevisionRepository workflowRevisionRepository;
  private final TaskExecutionService taskExecutionService;
  private final WorkflowRunService workflowRunService;
  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final ActionRepository actionRepository;
  private final DispatcherRepository dispatcherRepository;

  @Value("${flow.watcher.enabled:true}")
  private boolean enabled;

  // Hard pruning of tombstoned Workflows ships off - the retention policy is decided separately.
  @Value("${flow.watcher.retention.enabled:false}")
  private boolean retentionEnabled;

  public WorkflowWatcher(
      TaskRunService taskRunService,
      WorkflowRunRepository workflowRunRepository,
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      TaskExecutionService taskExecutionService,
      WorkflowRunService workflowRunService,
      WorkflowRunStateHelper workflowRunStateHelper,
      ActionRepository actionRepository,
      DispatcherRepository dispatcherRepository) {
    this.taskRunService = taskRunService;
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.taskExecutionService = taskExecutionService;
    this.workflowRunService = workflowRunService;
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.actionRepository = actionRepository;
    this.dispatcherRepository = dispatcherRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (enabled) {
      sweep();
    }
  }

  @Scheduled(
      initialDelayString =
          "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(30000)}",
      fixedDelayString = "${flow.watcher.interval-ms:30000}")
  public void sweep() {
    // The kill switch stops the scheduled sweeps too, not just the boot sweep - a disabled
    // watcher runs nothing on its own. Tests drive individual sweeps by direct invocation.
    if (!enabled) {
      return;
    }
    // Each sweep is isolated: the paged queries carry a server-side time limit that throws rather
    // than returning a short page, so an unisolated failure in an early sweep would stop every
    // sweep after it - silently ending timeout reaping and stale-claim recovery.
    SweepRunner.runIsolated("reapTaskTimeouts", this::reapTaskTimeouts, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapWorkflowTimeouts", this::reapWorkflowTimeouts, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("recoverStalledRuns", this::recoverStalledRuns, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("finalizeWorkspacelessRuns", this::finalizeWorkspacelessRuns, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("resumeDueWaitingTasks", this::resumeDueWaitingTasks, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("cancelDeletedWorkflowRuns", this::cancelDeletedWorkflowRuns, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("pruneDeletedWorkflows", this::pruneDeletedWorkflows, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapRunsWithMissingRevision", this::reapRunsWithMissingRevision, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapClaimsFromGoneDispatchers", this::reapClaimsFromGoneDispatchers, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapExpiredLeases", this::reapExpiredLeases, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("closeStrayActions", this::closeStrayActions, WorkflowWatcher::logSweepFailure);
  }

  private static void logSweepFailure(String sweep, Exception ex) {
    LOGGER.error("Sweep {} failed; the remaining sweeps in this cycle still ran.", sweep, ex);
  }

  /**
   * Reap TaskRuns past their durable {@code timeoutAt} deadline. A requeueable task with retry
   * budget left is requeued (this is also the crash recovery for a killed claimant - no lease is
   * required); otherwise the task is marked timed out and driven through the normal end path.
   * Both transitions are fenced on the observed claim seq, so a claim racing the reap wins.
   */
  public void reapTaskTimeouts() {
    SweepRunner.forEachIsolated(
        taskRunService.findReapable(new Date(), PAGE_SIZE),
        taskRun -> {
          Long observedSeq = (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null;
          int attempts = (taskRun.getRetry() != null) ? taskRun.getRetry().getCount() : 0;
          if (REQUEUEABLE_TYPES.contains(taskRun.getType()) && attempts < MAX_RETRIES) {
            if (taskRunService.tryRequeue(
                    taskRun.getId(), observedSeq, Backoff.nextRetryAt(attempts), attempts + 1)
                != null) {
              LOGGER.info(
                  "[{}] TaskRun timed out. Requeued as attempt {}.", taskRun.getId(), attempts + 1);
            }
          } else if (taskRunService.tryTimeout(
                  taskRun.getId(),
                  observedSeq,
                  MessageFormatter.format(
                          "The TaskRun exceeded the timeout. Timeout was set to {} minutes",
                          taskRun.getTimeout())
                      .getMessage())
              != null) {
            LOGGER.info("[{}] TaskRun timed out. Retry budget exhausted.", taskRun.getId());
            taskExecutionService.end(taskRun.getId());
          }
        },
        (taskRun, ex) ->
            LOGGER.error("[{}] Task timeout reap failed: {}", taskRun.getId(), ex.getMessage()));
  }

  /** Reap running WorkflowRuns past their durable {@code timeoutAt} deadline. */
  public void reapWorkflowTimeouts() {
    SweepRunner.forEachIsolated(
        workflowRunStateHelper.findTimedOut(new Date(), PAGE_SIZE),
        wfRun -> workflowRunService.timeout(wfRun.getId(), false),
        (wfRun, ex) ->
            LOGGER.error("[{}] Workflow timeout reap failed: {}", wfRun.getId(), ex.getMessage()));
  }

  /**
   * Recover active runs with zero in-flight TaskRuns - the advancing winner was lost (for
   * example a crash between a task's completion and queueing its dependants). The in-flight
   * check is a count, not a load; the recovery itself is made of no-op-safe transitions.
   */
  public void recoverStalledRuns() {
    Date startedBefore = new Date(System.currentTimeMillis() - STALL_GRACE_MILLIS);
    SweepRunner.forEachIsolated(
        workflowRunStateHelper.findRunningStartedBefore(startedBefore, PAGE_SIZE),
        wfRun -> {
          if (!taskRunService.existsInFlightByWorkflowRunRef(wfRun.getId())) {
            LOGGER.info(
                "[{}] Active WorkflowRun has no in-flight TaskRuns. Recovering advance.",
                wfRun.getId());
            taskExecutionService.advance(wfRun.getId());
          }
        },
        (wfRun, ex) ->
            LOGGER.error("[{}] Stalled-run recovery failed: {}", wfRun.getId(), ex.getMessage()));
  }

  /**
   * Finalize completed runs that have no workspaces: with nothing to tear down no agent ever
   * claims them, so the engine closes them out itself.
   */
  public void finalizeWorkspacelessRuns() {
    SweepRunner.forEachIsolated(
        workflowRunStateHelper.findFinalizableWithoutWorkspaces(PAGE_SIZE),
        wfRun -> {
          if (workflowRunStateHelper.tryFinalize(wfRun.getId()) != null) {
            LOGGER.info("[{}] Finalized workspace-less completed WorkflowRun.", wfRun.getId());
          }
        },
        (wfRun, ex) ->
            LOGGER.error("[{}] Finalize sweep failed: {}", wfRun.getId(), ex.getMessage()));
  }

  /**
   * Resume waiting tasks whose {@code waitUntil} has elapsed - a due sleep completes, a due
   * acquirelock re-attempts. Event and approval waits carry no {@code waitUntil}, so the sparse
   * index never surfaces them here. Each is claimed by a Compare-And-Set so instances never
   * double-drive.
   */
  public void resumeDueWaitingTasks() {
    SweepRunner.forEachIsolated(
        taskRunService.findWaitingDue(new Date(), PAGE_SIZE),
        task -> {
          if (taskRunService.tryStartWaitingResume(task.getId())) {
            taskExecutionService.resumeWaitingTask(task.getId());
          }
        },
        (task, ex) ->
            LOGGER.error("[{}] Waiting-task resume failed: {}", task.getId(), ex.getMessage()));
  }

  /**
   * Wind down deleted (tombstoned) Workflows: cancel their still-in-flight WorkflowRuns through the
   * normal cancel path. Nothing is destroyed - hard pruning is the separate retention sweep.
   */
  public void cancelDeletedWorkflowRuns() {
    for (WorkflowEntity workflow : workflowRepository.findByStatus(WorkflowStatus.deleted)) {
      SweepRunner.forEachIsolated(
          workflowRunRepository.findByWorkflowRefAndPhaseIn(workflow.getId(), IN_FLIGHT_PHASES),
          wfRun -> {
            workflowRunService.cancel(wfRun.getId());
            LOGGER.info(
                "[{}] Cancelled in-flight run of deleted Workflow {}.",
                wfRun.getId(),
                workflow.getId());
          },
          (wfRun, ex) ->
              LOGGER.error(
                  "[{}] Deleted-workflow run cancel failed: {}", wfRun.getId(), ex.getMessage()));
    }
  }

  /**
   * Retention sweep for deleted Workflows once their runs have finalised. Ships disabled - the
   * pruning policy (what to keep, for how long) is a separate ruling; enabling it hard-deletes.
   */
  public void pruneDeletedWorkflows() {
    if (!retentionEnabled) {
      return;
    }
    // Intentionally a no-op until the retention policy is ruled and this sweep is implemented.
  }

  /**
   * Fail outright an in-flight WorkflowRun whose revision no longer resolves. The revision is
   * required to walk the DAG for tasks not yet materialised, so those are simply never created;
   * any TaskRuns already materialised are fetched directly by ref (bypassing the revision walk)
   * and cancelled the same way the normal cancel path treats them.
   */
  public void reapRunsWithMissingRevision() {
    SweepRunner.forEachIsolated(
        workflowRunStateHelper.findInFlight(PAGE_SIZE),
        wfRun -> {
          if (workflowRevisionRepository.existsById(wfRun.getWorkflowRevisionRef())) {
            return;
          }
          long duration =
              wfRun.getStartTime() != null
                  ? new Date().getTime() - wfRun.getStartTime().getTime()
                  : 0;
          if (workflowRunStateHelper.tryComplete(
                  wfRun.getId(),
                  IN_FLIGHT_PHASES,
                  RunStatus.invalid,
                  "The WorkflowRun's revision no longer resolves.",
                  duration)
              != null) {
            LOGGER.error(
                "[{}] WorkflowRun revision {} no longer resolves. Failed outright.",
                wfRun.getId(),
                wfRun.getWorkflowRevisionRef());
            taskRunService
                .findNonTerminalByWorkflowRunRef(wfRun.getId())
                .forEach(
                    t -> {
                      if (RunPhase.pending.equals(t.getPhase())) {
                        taskExecutionService.queue(t.getId());
                      } else {
                        taskExecutionService.end(t.getId());
                      }
                    });
          }
        },
        (wfRun, ex) ->
            LOGGER.error("[{}] Missing-revision reap failed: {}", wfRun.getId(), ex.getMessage()));
  }

  /**
   * Reap TaskRuns claimed by a dispatcher that has gone quiet - deregistered, or simply never
   * connected again - ahead of (or without) the task's own deadline. Same crash-recovery
   * treatment as a deadline reap: a requeueable task with retry budget left is requeued;
   * otherwise it is abandoned and driven through the normal end path.
   */
  public void reapClaimsFromGoneDispatchers() {
    SweepRunner.forEachIsolated(
        taskRunService.findClaimed(PAGE_SIZE),
        taskRun -> {
          String dispatcherRef = (taskRun.getClaim() != null) ? taskRun.getClaim().getBy() : null;
          if (dispatcherRef == null || isDispatcherLive(dispatcherRef)) {
            return;
          }
          requeueOrAbandon(
              taskRun,
              MessageFormatter.format("The claimant {} is no longer registered.", dispatcherRef)
                  .getMessage(),
              "DispatcherGone");
        },
        (taskRun, ex) ->
            LOGGER.error(
                "[{}] Stale-dispatcher reap failed: {}", taskRun.getId(), ex.getMessage()));
  }

  /**
   * Reap TaskRuns whose lease has expired - a dispatcher that heartbeated at least once and then
   * went quiet without its registration itself going stale (that case is {@link
   * #reapClaimsFromGoneDispatchers}). Same crash-recovery treatment: a requeueable task with
   * retry budget left is requeued; otherwise it is abandoned and driven through the normal end
   * path.
   */
  public void reapExpiredLeases() {
    SweepRunner.forEachIsolated(
        taskRunService.findLeaseExpired(PAGE_SIZE),
        taskRun ->
            requeueOrAbandon(
                taskRun,
                MessageFormatter.format(
                        "The claimant {}'s lease expired.",
                        taskRun.getClaim() != null ? taskRun.getClaim().getBy() : null)
                    .getMessage(),
                "LeaseExpired"),
        (taskRun, ex) ->
            LOGGER.error("[{}] Lease-expiry reap failed: {}", taskRun.getId(), ex.getMessage()));
  }

  // Shared crash-recovery treatment for a TaskRun whose claimant is confirmed gone (dispatcher
  // deregistered/stale, or its lease lapsed): a requeueable task with retry budget left is
  // requeued for another attempt; otherwise it is abandoned (terminal timedout) and driven
  // through the normal end path. statusReason records which of the two triggered the reap.
  private void requeueOrAbandon(TaskRunEntity taskRun, String statusMessage, String statusReason) {
    Long observedSeq = (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null;
    int attempts = (taskRun.getRetry() != null) ? taskRun.getRetry().getCount() : 0;
    if (REQUEUEABLE_TYPES.contains(taskRun.getType()) && attempts < MAX_RETRIES) {
      if (taskRunService.tryRequeue(
              taskRun.getId(), observedSeq, Backoff.nextRetryAt(attempts), attempts + 1)
          != null) {
        LOGGER.info(
            "[{}] {} Requeued as attempt {}.", taskRun.getId(), statusMessage, attempts + 1);
      }
    } else if (taskRunService.tryAbandon(taskRun.getId(), observedSeq, statusMessage, statusReason)
        != null) {
      LOGGER.info("[{}] {} Retry budget exhausted.", taskRun.getId(), statusMessage);
      taskExecutionService.end(taskRun.getId());
    }
  }

  private boolean isDispatcherLive(String dispatcherRef) {
    Optional<DispatcherEntity> dispatcher = dispatcherRepository.findById(dispatcherRef);
    if (dispatcher.isEmpty() || dispatcher.get().getLastConnectedDate() == null) {
      return false;
    }
    return dispatcher.get().getLastConnectedDate().getTime()
        > System.currentTimeMillis() - DISPATCHER_STALE_MILLIS;
  }

  /**
   * Close Actions left open (submitted) by a WorkflowRun that has already gone terminal. The
   * normal execution loop only ever resolves an Action by ending its TaskRun, which cannot
   * happen once the run is terminal - a submitted Action found here can only be one orphaned by
   * a cancel or timeout that raced ahead of the user's response.
   */
  public void closeStrayActions() {
    SweepRunner.forEachIsolated(
        actionRepository.findByStatusOrderByCreationDateAsc(
            ActionStatus.submitted, PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "creationDate"))),
        action -> {
          WorkflowRunEntity wfRun =
              workflowRunRepository.findById(action.getWorkflowRunRef()).orElse(null);
          if (wfRun != null && IN_FLIGHT_PHASES.contains(wfRun.getPhase())) {
            return;
          }
          if (actionRepository.updateStatusByIdAndStatus(
                  action.getId(), ActionStatus.submitted, ActionStatus.cancelled)
              > 0) {
            LOGGER.info(
                "[{}] Closed stray Action left open by terminal WorkflowRun {}.",
                action.getId(),
                action.getWorkflowRunRef());
          }
        },
        (action, ex) ->
            LOGGER.error("[{}] Stray-action close failed: {}", action.getId(), ex.getMessage()));
  }
}
