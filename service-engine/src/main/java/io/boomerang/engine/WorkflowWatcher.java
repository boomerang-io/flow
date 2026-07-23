package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RetryClass;
import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

  private static final int PAGE_SIZE = 50;

  // A run must have been started at least this long before it is checked for a lost advance,
  // so freshly-started runs whose first tasks are still being queued are not churned.
  private static final long STALL_GRACE_MILLIS = 60000;

  // Only agent-executed types are requeued on timeout - that is the crash recovery for a killed
  // claimant. Gates, waits and inline system tasks time out terminally, as they always have.
  private static final Set<TaskType> REQUEUEABLE_TYPES =
      EnumSet.of(TaskType.template, TaskType.custom, TaskType.script, TaskType.generic);

  private final TaskRunRepository taskRunRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final TaskExecutionService taskExecutionService;
  private final WorkflowRunService workflowRunService;

  @Value("${flow.watcher.enabled:true}")
  private boolean enabled;

  public WorkflowWatcher(
      TaskRunRepository taskRunRepository,
      WorkflowRunRepository workflowRunRepository,
      TaskExecutionService taskExecutionService,
      WorkflowRunService workflowRunService) {
    this.taskRunRepository = taskRunRepository;
    this.workflowRunRepository = workflowRunRepository;
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
    redriveStalledRuns();
    finalizeWorkspacelessRuns();
  }

  /**
   * Reap TaskRuns past their durable {@code timeoutAt} deadline. A requeueable task with retry
   * budget left is requeued (this is also the crash recovery for a killed claimant - no lease is
   * required); otherwise the task is marked timed out and driven through the normal end path.
   * Both transitions are fenced on the observed claim seq, so a claim racing the reap wins.
   */
  public void reapTaskTimeouts() {
    for (TaskRunEntity taskRun : taskRunRepository.findReapable(new Date(), PAGE_SIZE)) {
      try {
        Long observedSeq = (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null;
        RetryClass retryClass =
            (taskRun.getRetry() != null && taskRun.getRetry().getClazz() != null)
                ? taskRun.getRetry().getClazz()
                : RetryClass.generic;
        int attempts = (taskRun.getRetry() != null) ? taskRun.getRetry().getCount() : 0;
        if (REQUEUEABLE_TYPES.contains(taskRun.getType()) && canRetry(retryClass, attempts)) {
          if (taskRunRepository.tryRequeue(
                  taskRun.getId(),
                  observedSeq,
                  nextRetryAt(retryClass, attempts),
                  attempts + 1,
                  retryClass)
              != null) {
            LOGGER.info(
                "[{}] TaskRun timed out. Requeued as attempt {} ({}).",
                taskRun.getId(),
                attempts + 1,
                retryClass);
          }
        } else if (taskRunRepository.tryTimeout(taskRun.getId(), observedSeq) != null) {
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
    for (WorkflowRunEntity wfRun : workflowRunRepository.findTimedOut(new Date(), PAGE_SIZE)) {
      try {
        workflowRunService.timeout(wfRun.getId(), false);
      } catch (Exception ex) {
        LOGGER.error("[{}] Workflow timeout reap failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Re-drive active runs with zero in-flight TaskRuns - the advancing winner was lost (for
   * example a crash between a task's completion and queueing its dependants). The in-flight
   * check is a count, not a load; the re-drive itself is made of no-op-safe transitions.
   */
  public void redriveStalledRuns() {
    Date startedBefore = new Date(System.currentTimeMillis() - STALL_GRACE_MILLIS);
    for (WorkflowRunEntity wfRun :
        workflowRunRepository.findRunningStartedBefore(startedBefore, PAGE_SIZE)) {
      try {
        if (!taskRunRepository.existsInFlightByWorkflowRunRef(wfRun.getId())) {
          LOGGER.info("[{}] Active WorkflowRun has no in-flight TaskRuns. Re-driving advance.",
              wfRun.getId());
          taskExecutionService.advance(wfRun.getId());
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Stalled-run re-drive failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
  }

  /**
   * Finalize completed runs that have no workspaces: with nothing to tear down no agent ever
   * claims them, so the engine closes them out itself.
   */
  public void finalizeWorkspacelessRuns() {
    for (WorkflowRunEntity wfRun :
        workflowRunRepository.findFinalizableWithoutWorkspaces(PAGE_SIZE)) {
      try {
        if (workflowRunRepository.tryFinalize(wfRun.getId()) != null) {
          LOGGER.info("[{}] Finalized workspace-less completed WorkflowRun.", wfRun.getId());
        }
      } catch (Exception ex) {
        LOGGER.error("[{}] Finalize sweep failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
  }

  // Retry policy per class: generic 10s base, x2, cap 3, ceiling 5m; ratelimit 30s base, x2,
  // cap 6, ceiling 10m; terminal never retries. Classification is typed - never string-matched.
  private static boolean canRetry(RetryClass retryClass, int attempts) {
    return switch (retryClass) {
      case generic -> attempts < 3;
      case ratelimit -> attempts < 6;
      case terminal -> false;
    };
  }

  private static Date nextRetryAt(RetryClass retryClass, int attempts) {
    long base = (RetryClass.ratelimit == retryClass) ? 30000 : 10000;
    long ceiling = (RetryClass.ratelimit == retryClass) ? 600000 : 300000;
    long jitter = ThreadLocalRandom.current().nextLong((RetryClass.ratelimit == retryClass) ? 15000 : 5000);
    long backoff = Math.min(base * (1L << Math.min(attempts, 30)), ceiling);
    return new Date(System.currentTimeMillis() + backoff + jitter);
  }
}
