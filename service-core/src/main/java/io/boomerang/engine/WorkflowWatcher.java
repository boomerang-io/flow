package io.boomerang.engine;

import io.boomerang.common.model.StatusReason;
import io.boomerang.workflow.ArtifactService;
import io.boomerang.workflow.WorkflowRetentionService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.common.entity.ActionEntity;
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
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
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

  private static final Set<TaskType> REQUEUEABLE_TYPES = EngineConstants.REQUEUEABLE_TYPES;

  private static final int MAX_RETRIES = EngineConstants.MAX_RETRIES;

  // A run is provisioned at most this many times; a stale claim on the last attempt fails it.
  private static final int MAX_PROVISION_ATTEMPTS = 3;

  private static final List<RunPhase> IN_FLIGHT_PHASES =
      List.of(RunPhase.pending, RunPhase.queued, RunPhase.running);

  // A dispatcher is treated as gone once it has not connected for this long. Each queue poll
  // refreshes lastConnectedDate as it starts, at least every 30 s (the longest the engine holds a
  // poll), so this is two missed polls - short enough that a dead dispatcher's claims are
  // recovered promptly, long enough that a GC pause or network blip does not reap a healthy one.
  // Recovering a claim re-dispatches the task while the original executor may still be running
  // it, and nothing kills that executor, so this cannot be tightened further until the dispatch
  // protocol can cancel in-flight work.
  private static final long DISPATCHER_STALE_MILLIS = 60000;

  private final TaskRunService taskRunService;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRepository workflowRepository;
  private final TaskExecutionService taskExecutionService;
  private final WorkflowExecutionService workflowExecutionService;
  private final WorkflowRunService workflowRunService;
  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final ActionRepository actionRepository;
  private final DispatcherRepository dispatcherRepository;
  private final ArtifactService artifactService;
  private final WorkflowRetentionService workflowRetentionService;
  private final MongoTemplate mongoTemplate;

  // Where the sweeps that page oldest first resume, so candidates they leave alone cannot hide the
  // ones behind them.
  private final SweepCursor stalledRuns = new SweepCursor("startTime");
  private final SweepCursor foreachParents = new SweepCursor("startTime");
  private final SweepCursor strayActions = new SweepCursor("creationDate");

  @Value("${flow.watcher.enabled:true}")
  private boolean enabled;

  // How long a provisioning claim may go without the run starting before it is treated as lost.
  // Well above the dispatcher's per-claim storage wait (kube.timeout.waitUntil, 30s by default).
  @Value("${flow.watcher.provision-claim-grace-ms:300000}")
  private long provisionClaimGraceMillis;

  // How long an upload may stay unconfirmed before its record and any partial file are removed.
  // Well above the upload link's own lifetime (flow.artifacts.link-ttl-minutes, 15 by default).
  @Value("${flow.artifacts.upload-grace-minutes:60}")
  private long artifactUploadGraceMinutes;

  public WorkflowWatcher(
      TaskRunService taskRunService,
      WorkflowRunRepository workflowRunRepository,
      WorkflowRepository workflowRepository,
      TaskExecutionService taskExecutionService,
      WorkflowExecutionService workflowExecutionService,
      WorkflowRunService workflowRunService,
      WorkflowRunStateHelper workflowRunStateHelper,
      ActionRepository actionRepository,
      DispatcherRepository dispatcherRepository,
      ArtifactService artifactService,
      WorkflowRetentionService workflowRetentionService,
      MongoTemplate mongoTemplate) {
    this.taskRunService = taskRunService;
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRepository = workflowRepository;
    this.taskExecutionService = taskExecutionService;
    this.workflowExecutionService = workflowExecutionService;
    this.workflowRunService = workflowRunService;
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.actionRepository = actionRepository;
    this.dispatcherRepository = dispatcherRepository;
    this.artifactService = artifactService;
    this.workflowRetentionService = workflowRetentionService;
    this.mongoTemplate = mongoTemplate;
  }

  /** Start every cursored sweep's next page from its oldest candidate. */
  public void resetSweepCursors() {
    stalledRuns.reset();
    foreachParents.reset();
    strayActions.reset();
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
    SweepRunner.runIsolated("recoverForeachTasks", this::recoverForeachTasks, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("resumeDueWaitingTasks", this::resumeDueWaitingTasks, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("cancelDeletedWorkflowRuns", this::cancelDeletedWorkflowRuns, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("pruneDeletedWorkflows", this::pruneDeletedWorkflows, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapRunsWithMissingRevision", this::reapRunsWithMissingRevision, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("recoverStaleProvisionClaims", this::recoverStaleProvisionClaims, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapClaimsFromGoneDispatchers", this::reapClaimsFromGoneDispatchers, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapExpiredLeases", this::reapExpiredLeases, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("closeStrayActions", this::closeStrayActions, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("expireArtifacts", this::expireArtifacts, WorkflowWatcher::logSweepFailure);
    SweepRunner.runIsolated("reapStaleArtifactUploads", this::reapStaleArtifactUploads, WorkflowWatcher::logSweepFailure);
  }

  /**
   * Expire artifacts past their expiration date: the file is deleted and the record kept, so the
   * run still shows what it produced. Does nothing when no artifact store is configured.
   */
  public void expireArtifacts() {
    artifactService.expireArtifacts(PAGE_SIZE);
  }

  /** Remove uploads that were never confirmed: the upload task died before it finished. */
  public void reapStaleArtifactUploads() {
    artifactService.reapStaleUploads(Duration.ofMinutes(artifactUploadGraceMinutes), PAGE_SIZE);
  }

  // Retention runs on its own, slower clock: hourly by default, never on a disabled watcher.
  @Scheduled(
      initialDelayString =
          "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(60000)}",
      fixedDelayString = "${flow.watcher.retention-interval-ms:3600000}")
  public void purgeExpiredRunsOnSchedule() {
    if (enabled) {
      SweepRunner.runIsolated(
          "purgeExpiredRuns", this::purgeExpiredRuns, WorkflowWatcher::logSweepFailure);
    }
  }

  /** Purge finished runs past the Activity retention (see {@link WorkflowRetentionService}). */
  public void purgeExpiredRuns() {
    workflowRetentionService.purgeExpiredRuns();
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
        taskRun ->
            requeueOrAbandon(
                taskRun,
                MessageFormatter.format(
                        "The TaskRun exceeded the timeout. Timeout was set to {} minutes",
                        taskRun.getTimeout())
                    .getMessage(),
                StatusReason.DEADLINE_EXCEEDED,
                true),
        (taskRun, ex) ->
            LOGGER.error("[{}] Task timeout reap failed: {}", taskRun.getId(), ex.getMessage()));
  }

  /**
   * Reap WorkflowRuns past their durable {@code timeoutAt} deadline: running ones, and pending
   * ones still waiting for a dispatcher to provision their workspace.
   */
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
    List<WorkflowRunEntity> page =
        workflowRunStateHelper.findRunningStartedBefore(startedBefore, PAGE_SIZE, stalledRuns);
    if (page.isEmpty()) {
      return;
    }
    Set<String> inFlight =
        taskRunService.runsWithTasksInFlight(
            page.stream().map(WorkflowRunEntity::getId).toList());
    SweepRunner.forEachIsolated(
        page,
        wfRun -> {
          if (!inFlight.contains(wfRun.getId())) {
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
   * Recover foreach tasks running with no item in flight - a crash between the parent's fan-out
   * and its last item, between its last item ending and the parent completing, or between the
   * parent's outcome being recorded and its end. A running parent counts as in flight, so {@link
   * #recoverStalledRuns} never reaches it. A parent with its outcome recorded is ended - the
   * completion Compare-And-Set makes a repeated end a no-op. Any other is recovered by the same
   * idempotent fan-out the live path runs: missing items are created, unqueued items queued, and a
   * parent whose items are all terminal completed. A paused run's fan-out is left to resume.
   */
  public void recoverForeachTasks() {
    Date startedBefore = new Date(System.currentTimeMillis() - STALL_GRACE_MILLIS);
    List<TaskRunEntity> page =
        taskRunService.findRunningForeachParents(startedBefore, PAGE_SIZE, foreachParents);
    if (page.isEmpty()) {
      return;
    }
    // A paused run's pending items are held by the pause gate; fanning out would only queue each
    // one to be refused again. Resume re-runs the fan-out through advance.
    Set<String> paused =
        workflowRunStateHelper.pausedAmong(
            page.stream().map(TaskRunEntity::getWorkflowRunRef).distinct().toList());
    SweepRunner.forEachIsolated(
        page,
        parent -> {
          if (!RunStatus.running.equals(parent.getStatus())) {
            LOGGER.info(
                "[{}] Foreach TaskRun has its outcome recorded but was never ended. Ending it.",
                parent.getId());
            taskExecutionService.end(parent.getId(), Optional.empty(), Optional.empty());
          } else if (!taskRunService.existsInFlightItem(parent.getId())
              && !paused.contains(parent.getWorkflowRunRef())) {
            LOGGER.info(
                "[{}] Running foreach TaskRun has no item in flight. Recovering its fan-out.",
                parent.getId());
            taskExecutionService.fanOutItems(parent.getId());
          }
        },
        (parent, ex) ->
            LOGGER.error("[{}] Foreach recovery failed: {}", parent.getId(), ex.getMessage()));
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
   * normal cancel path. Nothing is destroyed here - pruneDeletedWorkflows hard-deletes once
   * the runs complete.
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
   * Prune deleted (tombstoned) Workflows whose runs have all completed (see {@link
   * WorkflowRetentionService}).
   */
  public void pruneDeletedWorkflows() {
    workflowRetentionService.pruneDeletedWorkflows();
  }

  /**
   * Fail outright an in-flight WorkflowRun whose revision no longer resolves. The revision is
   * required to walk the DAG for tasks not yet materialised, so those are simply never created;
   * any TaskRuns already materialised are fetched directly by ref (bypassing the revision walk)
   * and cancelled the same way the normal cancel path treats them.
   */
  public void reapRunsWithMissingRevision() {
    // Resolve the missing revisions first, then page only the runs that reference them: a page
    // of in-flight runs whose revisions all exist would otherwise be re-read every tick and
    // never advance past them, leaving a newer orphan unreaped for as long as the backlog held.
    List<String> referenced = workflowRunStateHelper.findInFlightRevisionRefs();
    if (referenced.isEmpty()) {
      return;
    }
    Set<String> existing = workflowRunStateHelper.existingRevisions(referenced);
    List<String> missing = referenced.stream().filter(ref -> !existing.contains(ref)).toList();
    if (missing.isEmpty()) {
      return;
    }
    SweepRunner.forEachIsolated(
        workflowRunStateHelper.findInFlightWithRevisionIn(missing, PAGE_SIZE),
        wfRun -> {
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
            workflowExecutionService.cancelPendingAndRunningTasks(wfRun);
          }
        },
        (wfRun, ex) ->
            LOGGER.error("[{}] Missing-revision reap failed: {}", wfRun.getId(), ex.getMessage()));
  }

  /**
   * Recover WorkflowRuns claimed for workspace provisioning that never started - the dispatcher
   * failed to provision, or died holding the claim. The claim is released back to claimable for the
   * next dispatcher poll to retry; a run whose last allowed attempt went stale is failed instead.
   * Both transitions are fenced on the observed claim seq, so a start or a fresh claim wins.
   */
  public void recoverStaleProvisionClaims() {
    Date claimedBefore = new Date(System.currentTimeMillis() - provisionClaimGraceMillis);
    SweepRunner.forEachIsolated(
        workflowRunStateHelper.findStaleProvisionClaims(claimedBefore, PAGE_SIZE),
        wfRun -> {
          long attempts = wfRun.getClaim().getSeq();
          if (attempts < MAX_PROVISION_ATTEMPTS) {
            if (workflowRunStateHelper.tryReleaseProvisionClaim(wfRun.getId(), attempts) != null) {
              LOGGER.info(
                  "[{}] Provisioning claim by {} went stale. Released after attempt {}.",
                  wfRun.getId(),
                  wfRun.getClaim().getBy(),
                  attempts);
            }
          } else if (workflowRunStateHelper.tryFailProvision(
                  wfRun.getId(),
                  attempts,
                  MessageFormatter.format(
                          "Workspace provisioning did not complete after {} attempts.", attempts)
                      .getMessage())
              != null) {
            LOGGER.error(
                "[{}] Workspace provisioning did not complete after {} attempts. Run failed.",
                wfRun.getId(),
                attempts);
            workflowExecutionService.cancelPendingAndRunningTasks(wfRun);
          }
        },
        (wfRun, ex) ->
            LOGGER.error(
                "[{}] Stale provisioning-claim recovery failed: {}", wfRun.getId(), ex.getMessage()));
  }

  /**
   * Reap TaskRuns claimed by a dispatcher that has gone quiet - deregistered, or simply never
   * connected again - ahead of (or without) the task's own deadline. Same crash-recovery
   * treatment as a deadline reap: a requeueable task with retry budget left is requeued;
   * otherwise it is abandoned and driven through the normal end path.
   */
  public void reapClaimsFromGoneDispatchers() {
    // The live dispatchers are read once, and only the claims the others hold are paged: claims of
    // live dispatchers never fill the page ahead of a gone one's.
    List<String> live =
        dispatcherRepository
            .findConnectedSince(new Date(System.currentTimeMillis() - DISPATCHER_STALE_MILLIS))
            .stream()
            .map(DispatcherEntity::getId)
            .toList();
    SweepRunner.forEachIsolated(
        taskRunService.findClaimedByOthers(live, PAGE_SIZE),
        taskRun -> {
          String dispatcherRef = taskRun.getClaim().getBy();
          requeueOrAbandon(
              taskRun,
              MessageFormatter.format("The claimant {} is no longer registered.", dispatcherRef)
                  .getMessage(),
              StatusReason.DISPATCHER_GONE,
              false);
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
                StatusReason.LEASE_EXPIRED,
                false),
        (taskRun, ex) ->
            LOGGER.error("[{}] Lease-expiry reap failed: {}", taskRun.getId(), ex.getMessage()));
  }

  // Shared treatment for a TaskRun past its deadline or whose claimant is confirmed gone
  // (dispatcher deregistered/stale, or its lease lapsed): a requeueable task with retry budget left
  // is requeued for another attempt; otherwise it is abandoned (terminal timedout) and driven
  // through the normal end path. statusReason records which reap triggered it; pastDeadline makes
  // the abandon hold only while timeoutAt has elapsed. Both transitions are fenced on the observed
  // claim seq, so a claim racing the reap wins.
  private void requeueOrAbandon(
      TaskRunEntity taskRun, String statusMessage, String statusReason, boolean pastDeadline) {
    Long observedSeq = (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null;
    int attempts = (taskRun.getRetry() != null) ? taskRun.getRetry().getCount() : 0;
    if (REQUEUEABLE_TYPES.contains(taskRun.getType()) && attempts < MAX_RETRIES) {
      if (taskRunService.tryRequeue(
              taskRun.getId(), observedSeq, Backoff.nextRetryAt(attempts), attempts + 1)
          != null) {
        LOGGER.info(
            "[{}] {} Requeued as attempt {}.", taskRun.getId(), statusMessage, attempts + 1);
      }
    } else if (taskRunService.tryAbandon(
            taskRun.getId(), observedSeq, statusMessage, statusReason, pastDeadline)
        != null) {
      LOGGER.info("[{}] {} Retry budget exhausted.", taskRun.getId(), statusMessage);
      taskExecutionService.end(taskRun.getId());
    }
  }

  /**
   * Close Actions left open (submitted) by a WorkflowRun that has already gone terminal. The
   * normal execution loop only ever resolves an Action by ending its TaskRun, which cannot
   * happen once the run is terminal - a submitted Action found here can only be one orphaned by
   * a cancel or timeout that raced ahead of the user's response.
   */
  public void closeStrayActions() {
    List<ActionEntity> page =
        strayActions.read(
            Criteria.where("status").is(ActionStatus.submitted),
            PAGE_SIZE,
            query -> {
              query.fields().include("_id").include("workflowRunRef").include("creationDate");
              return mongoTemplate.find(query, ActionEntity.class);
            },
            ActionEntity::getCreationDate,
            ActionEntity::getId);
    if (page.isEmpty()) {
      return;
    }
    Set<String> inFlight =
        workflowRunStateHelper.inFlightAmong(
            page.stream()
                .map(ActionEntity::getWorkflowRunRef)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    SweepRunner.forEachIsolated(
        page,
        action -> {
          // A run that is gone or terminal leaves its open Action stray; one with no run ref
          // was never a run's, and is left alone.
          if (action.getWorkflowRunRef() == null
              || inFlight.contains(action.getWorkflowRunRef())) {
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
