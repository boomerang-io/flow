package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.engine.repository.WorkflowRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

  private final TaskRunService taskRunService;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRepository workflowRepository;
  private final TaskExecutionService taskExecutionService;
  private final WorkflowRunService workflowRunService;

  @Value("${flow.watcher.enabled:true}")
  private boolean enabled;

  // Hard pruning of tombstoned Workflows ships off - the retention policy is decided separately.
  @Value("${flow.watcher.retention.enabled:false}")
  private boolean retentionEnabled;

  public WorkflowWatcher(
      TaskRunService taskRunService,
      WorkflowRunRepository workflowRunRepository,
      WorkflowRepository workflowRepository,
      TaskExecutionService taskExecutionService,
      WorkflowRunService workflowRunService) {
    this.taskRunService = taskRunService;
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRepository = workflowRepository;
    this.taskExecutionService = taskExecutionService;
    this.workflowRunService = workflowRunService;
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
    reapTaskTimeouts();
    reapWorkflowTimeouts();
    recoverStalledRuns();
    finalizeWorkspacelessRuns();
    resumeDueWaitingTasks();
    cancelDeletedWorkflowRuns();
    pruneDeletedWorkflows();
  }

  /**
   * Reap TaskRuns past their durable {@code timeoutAt} deadline. A requeueable task with retry
   * budget left is requeued (this is also the crash recovery for a killed claimant - no lease is
   * required); otherwise the task is marked timed out and driven through the normal end path.
   * Both transitions are fenced on the observed claim seq, so a claim racing the reap wins.
   */
  public void reapTaskTimeouts() {
    for (TaskRunEntity taskRun : taskRunService.findReapable(new Date(), PAGE_SIZE)) {
      try {
        Long observedSeq = (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null;
        int attempts = (taskRun.getRetry() != null) ? taskRun.getRetry().getCount() : 0;
        if (REQUEUEABLE_TYPES.contains(taskRun.getType()) && attempts < MAX_RETRIES) {
          if (taskRunService.tryRequeue(
                  taskRun.getId(), observedSeq, nextRetryAt(attempts), attempts + 1)
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
      } catch (Exception ex) {
        LOGGER.error("[{}] Task timeout reap failed: {}", taskRun.getId(), ex.getMessage());
      }
    }
  }

  /** Reap running WorkflowRuns past their durable {@code timeoutAt} deadline. */
  public void reapWorkflowTimeouts() {
    for (WorkflowRunEntity wfRun : workflowRunService.findTimedOut(new Date(), PAGE_SIZE)) {
      try {
        workflowRunService.timeout(wfRun.getId(), false);
      } catch (Exception ex) {
        LOGGER.error("[{}] Workflow timeout reap failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Recover active runs with zero in-flight TaskRuns - the advancing winner was lost (for
   * example a crash between a task's completion and queueing its dependants). The in-flight
   * check is a count, not a load; the recovery itself is made of no-op-safe transitions.
   */
  public void recoverStalledRuns() {
    Date startedBefore = new Date(System.currentTimeMillis() - STALL_GRACE_MILLIS);
    for (WorkflowRunEntity wfRun :
        workflowRunService.findRunningStartedBefore(startedBefore, PAGE_SIZE)) {
      try {
        if (!taskRunService.existsInFlightByWorkflowRunRef(wfRun.getId())) {
          LOGGER.info("[{}] Active WorkflowRun has no in-flight TaskRuns. Recovering advance.",
              wfRun.getId());
          taskExecutionService.advance(wfRun.getId());
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Stalled-run recovery failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Finalize completed runs that have no workspaces: with nothing to tear down no agent ever
   * claims them, so the engine closes them out itself.
   */
  public void finalizeWorkspacelessRuns() {
    for (WorkflowRunEntity wfRun :
        workflowRunService.findFinalizableWithoutWorkspaces(PAGE_SIZE)) {
      try {
        if (workflowRunService.tryFinalize(wfRun.getId()) != null) {
          LOGGER.info("[{}] Finalized workspace-less completed WorkflowRun.", wfRun.getId());
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Finalize sweep failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Resume waiting tasks whose {@code waitUntil} has elapsed - a due sleep completes, a due
   * acquirelock re-attempts. Event and approval waits carry no {@code waitUntil}, so the sparse
   * index never surfaces them here. Each is claimed by a Compare-And-Set so instances never
   * double-drive.
   */
  public void resumeDueWaitingTasks() {
    for (TaskRunEntity task : taskRunService.findWaitingDue(new Date(), PAGE_SIZE)) {
      try {
        if (taskRunService.tryStartWaitingResume(task.getId())) {
          taskExecutionService.resumeWaitingTask(task.getId());
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Waiting-task resume failed: {}", task.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Wind down deleted (tombstoned) Workflows: cancel their still-in-flight WorkflowRuns through the
   * normal cancel path. Nothing is destroyed - hard pruning is the separate retention sweep.
   */
  public void cancelDeletedWorkflowRuns() {
    for (WorkflowEntity workflow : workflowRepository.findByStatus(WorkflowStatus.deleted)) {
      for (WorkflowRunEntity wfRun :
          workflowRunRepository.findByWorkflowRefAndPhaseIn(workflow.getId(), IN_FLIGHT_PHASES)) {
        try {
          workflowRunService.cancel(wfRun.getId());
          LOGGER.info(
              "[{}] Cancelled in-flight run of deleted Workflow {}.", wfRun.getId(), workflow.getId());
        } catch (Exception ex) {
          LOGGER.error("[{}] Deleted-workflow run cancel failed: {}", wfRun.getId(), ex.getMessage());
        }
      }
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

  // Backoff: 10s base, x2 per attempt, 5m ceiling, jittered.
  private static Date nextRetryAt(int attempts) {
    long backoff = Math.min(10000L * (1L << Math.min(attempts, 30)), 300000);
    long jitter = ThreadLocalRandom.current().nextLong(5000);
    return new Date(System.currentTimeMillis() + backoff + jitter);
  }
}
