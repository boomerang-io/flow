package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.dispatcher.PollWakeups;
import io.boomerang.common.model.WorkflowConcurrency;
import io.boomerang.common.model.StatusReason;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.GraphProcessor;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WorkflowExecutionService {
  private static final Logger LOGGER = LogManager.getLogger(WorkflowExecutionService.class);

  @Autowired private WorkflowRunRepository workflowRunRepository;

  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  @Autowired private TaskRunRepository taskRunRepository;

  @Autowired private DAGUtility dagUtility;

  @Autowired @Lazy private TaskExecutionService taskExecutionService;

  @Autowired private ParameterManager paramManager;

  @Autowired @Lazy private WorkflowRunService workflowRunService;

  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;

  @Autowired private PollWakeups pollWakeups;


  @Autowired
  @Lazy
  @Qualifier("asyncWorkflowExecutor")
  TaskExecutor asyncWorkflowExecutor;

  public void queue(String wfRunId) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    WorkflowRunEntity wfRunEntity =
        workflowRunRepository
            .findById(wfRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    LOGGER.debug("[{}] Received queue WorkflowRun request.", wfRunEntity.getId());
    // Resolve the run's params; the admission below persists them.
    paramManager.resolveParamLayers(wfRunEntity, Optional.empty());

    final Optional<WorkflowRevisionEntity> optWorkflowRevisionEntity =
        this.workflowRevisionRepository.findById(wfRunEntity.getWorkflowRevisionRef());
    if (optWorkflowRevisionEntity.isPresent()) {
      WorkflowRevisionEntity wfRevisionEntity = optWorkflowRevisionEntity.get();
      final List<TaskRunEntity> tasks = dagUtility.createTaskList(wfRevisionEntity, wfRunEntity);
      LOGGER.info(
          "[{}] Found {} tasks: {}",
          wfRunEntity.getId(),
          tasks.size(),
          tasks.stream().map(TaskRunEntity::getName).collect(Collectors.joining(", ")));
      if (dagUtility.validateWorkflow(wfRunEntity, tasks)) {
        // The workflow's concurrency limit, checked before admission and again after it, the way
        // the workspace quota is: a burst of simultaneous admissions cannot each pass the first
        // count, and the recount keeps the oldest runs up to the limit and steps the rest back.
        WorkflowConcurrency concurrency = wfRevisionEntity.getConcurrency();
        boolean limited = concurrency != null && concurrency.limits();
        if (limited
            && workflowRunStateHelper.countInFlight(wfRunEntity.getWorkflowRef())
                >= concurrency.getLimit()) {
          overflow(wfRunEntity, concurrency);
          return;
        }
        // Admission Compare-And-Set: notstarted/pending (or a parked run) becomes ready, persisting
        // the resolved params in the same guarded write. A duplicate queue loses and performs no
        // side effects.
        if (workflowRunStateHelper.tryAdmit(wfRunId, wfRunEntity.getParams()) == null) {
          LOGGER.info("[{}] WorkflowRun already admitted. Nothing to do.", wfRunId);
          return;
        }
        if (limited
            && !workflowRunStateHelper.holdsSlot(
                wfRunId, wfRunEntity.getWorkflowRef(), concurrency.getLimit())) {
          overflow(wfRunEntity, concurrency);
        }
        return;
      }
    }
    // The run IS the record of this outcome: it is completed as invalid and the submit response
    // carries that status truthfully. Throwing here as well turned a half-persisted result into a
    // 500 for the caller. Starting an invalid run still fails loudly - start() rejects any phase
    // other than pending/queued. Completed through the Compare-And-Set so the end is published: a
    // child run that is invalid ends its waiting parent, and the run is audited like any other.
    workflowRunStateHelper.tryComplete(
        wfRunEntity.getId(),
        List.of(RunPhase.pending, RunPhase.queued),
        RunStatus.invalid,
        StatusReason.WORKFLOW_INVALID,
        "Failed to run workflow: incomplete, or invalid, workflow",
        0);
    LOGGER.error(
        "[{}] Failed to run workflow: incomplete, or invalid, workflow. Run marked invalid.",
        wfRunEntity.getId());
  }

  // A run over its workflow's limit: with overflow `queue` it parks until a run of the workflow
  // ends; with `skip` it ends now, a normal terminal run to its triggers and callers. A park or a
  // skip that loses its Compare-And-Set (a dispatcher claimed the run meanwhile) leaves the run
  // in flight over the limit, which the log records.
  private void overflow(WorkflowRunEntity wfRunEntity, WorkflowConcurrency concurrency) {
    if (concurrency.skips()) {
      if (workflowRunStateHelper.tryComplete(
              wfRunEntity.getId(),
              List.of(RunPhase.pending, RunPhase.queued),
              RunStatus.cancelled,
              StatusReason.CONCURRENCY_LIMIT,
              "Skipped: the workflow's concurrency limit of "
                  + concurrency.getLimit()
                  + " in-flight runs is reached.",
              0)
          != null) {
        LOGGER.info(
            "[{}] Over the workflow's concurrency limit of {}. Skipped.",
            wfRunEntity.getId(),
            concurrency.getLimit());
      }
      return;
    }
    if (workflowRunStateHelper.tryPark(
            wfRunEntity.getId(),
            "Waiting for a slot: the workflow's concurrency limit of "
                + concurrency.getLimit()
                + " in-flight runs is reached.")
        != null) {
      LOGGER.info(
          "[{}] Over the workflow's concurrency limit of {}. Parked for a slot.",
          wfRunEntity.getId(),
          concurrency.getLimit());
    }
  }

  /**
   * Re-drive a parked run through admission: when a run of its workflow ends, and on the sweep.
   * Admitted with nothing to provision it starts here; with a workspace it is claimable for
   * provisioning like any submit. Still over the limit, it stays parked.
   */
  public void readmit(String wfRunId) {
    queue(wfRunId);
    WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElse(null);
    if (run == null || !RunStatus.ready.equals(run.getStatus())) {
      return;
    }
    if (run.getWorkspace() == null) {
      start(wfRunId);
    } else {
      pollWakeups.runProvisionable();
    }
  }

  public CompletableFuture<Boolean> start(String wfRunId) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    WorkflowRunEntity wfRunEntity =
        workflowRunRepository
            .findById(wfRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    LOGGER.debug("[{}] Received start WorkflowRun request.", wfRunEntity.getId());

    // Check if Phase is valid.
    // Pending / Queued means it correctly came from queueTask() or Agent;
    if (!RunPhase.pending.equals(wfRunEntity.getPhase())
        && !RunPhase.queued.equals(wfRunEntity.getPhase())) {
      throw new BoomerangException(
          BoomerangError.WORKFLOWRUN_INVALID_PHASE,
          wfRunEntity.getPhase(),
          RunPhase.pending + " or " + RunPhase.queued);
    }
    // A run parked for a slot of its workflow holds none: its re-admission starts it, never a
    // caller's start.
    if (RunStatus.waiting.equals(wfRunEntity.getStatus())) {
      throw new BoomerangException(
          BoomerangError.WORKFLOWRUN_INVALID_PHASE,
          wfRunEntity.getPhase() + " (parked for a slot)",
          RunPhase.pending + " and admitted");
    }
    final List<TaskRunEntity> tasks = dagUtility.retrieveGraph(wfRunEntity.getId());
    final TaskRunEntity start = dagUtility.getTaskByType(tasks, TaskType.start);
    final TaskRunEntity end = dagUtility.getTaskByType(tasks, TaskType.end);
    final Graph<String, DefaultEdge> graph = dagUtility.createGraph(tasks);
    return CompletableFuture.supplyAsync(
        executeWorkflowAsync(
            wfRunEntity.getId(), wfRunEntity.getTimeout(), start, end, graph, tasks),
        asyncWorkflowExecutor);
  }

  public void cancel(String wfRunId) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    WorkflowRunEntity workflowExecution =
        workflowRunRepository
            .findById(wfRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    long duration = RunTimeouts.executionMillis(workflowExecution, new Date());
    // Completion Compare-And-Set: only a run that has not yet completed can be cancelled - a
    // terminal status is never overwritten.
    if (workflowRunStateHelper.tryComplete(
            wfRunId,
            List.of(RunPhase.pending, RunPhase.queued, RunPhase.running),
            RunStatus.cancelled,
            null,
            "The WorkflowRun was requested to be cancelled.",
            duration)
        == null) {
      LOGGER.info("[{}] WorkflowRun already completed. Nothing to cancel.", wfRunId);
      return;
    }

    // Cancel Running & Pending Tasks
    cancelPendingAndRunningTasks(workflowExecution);
  }

  private Supplier<Boolean> executeWorkflowAsync(
      String wfRunId,
      final Long timeout,
      final TaskRunEntity start,
      final TaskRunEntity end,
      final Graph<String, DefaultEdge> graph,
      final List<TaskRunEntity> tasksToRun) {
    return () -> {
      // Start Compare-And-Set: pending/queued becomes running exactly once, baking the durable
      // timeoutAt deadline. Only the winner queues the first tasks and schedules the timeout; a
      // duplicate start performs no side effects.
      WorkflowRunEntity wfRunEntity = workflowRunStateHelper.tryStart(wfRunId, new Date(), timeout);
      if (wfRunEntity == null) {
        LOGGER.info("[{}] WorkflowRun already started. Only the start winner proceeds.", wfRunId);
        return true;
      }
      // The durable timeoutAt deadline is baked at tryStart; the WorkflowWatcher's timeout
      // sweep is the single authoritative reaper - no per-run scheduled timer.
      LOGGER.info("[{}] Executing Workflow Async...", wfRunEntity.getId());
      try {
        List<TaskRunEntity> nextNodes = dagUtility.getTasksDependants(tasksToRun, start);
        LOGGER.debug("[{}] Next Nodes Size: {}", wfRunEntity.getId(), nextNodes.size());
        for (TaskRunEntity next : nextNodes) {
          final List<String> nodes =
              GraphProcessor.createOrderedTaskList(graph, start.getId(), end.getId());
          if (nodes.contains(next.getId())) {
            LOGGER.debug("[{}] Creating TaskRun ({})...", wfRunEntity.getId(), next.getId());
            taskExecutionService.queue(next.getId());
          }
        }
        // A scoped retry's carried-over tasks are already completed, so queueing refuses them and
        // nothing would end to queue their dependants: advance from them as a finished task would.
        if (wfRunEntity.getRetryFrom() != null) {
          taskExecutionService.advance(wfRunEntity.getId());
        }
      } catch (Exception e) {
        workflowRunStateHelper.tryComplete(
            wfRunEntity.getId(),
            List.of(RunPhase.running),
            RunStatus.invalid,
            StatusReason.WORKFLOW_INVALID,
            "Failed to run workflow: unable to process Workflow and queue all tasks.",
            RunTimeouts.executionMillis(wfRunEntity, new Date()));
        throw new BoomerangException(
            1000,
            "WORKFLOW_RUNTIME_EXCEPTION",
            "[{0}] Failed to run workflow: unable to process Workflow and queue all tasks",
            HttpStatus.INTERNAL_SERVER_ERROR,
            wfRunEntity.getId());
      }
      return true;
    };
  }

  // The timeout off the caller's thread, as an @Async method so a failure is logged like any other.
  @Async("asyncWorkflowExecutor")
  public void timeout(String wfRunId, String statusMessage) {
    timeoutWorkflow(wfRunId, statusMessage);
  }

  /*
   * Times out a running WorkflowRun, or a pending one no dispatcher provisioned within its
   * budget. Reached from the watcher timeout sweep and the task-timeout path - idempotent
   * against each other via the completion Compare-And-Set.
   */
  public void timeoutWorkflow(String wfRunId, String statusMessage) {
    LOGGER.debug("[{}] Timing out WorkflowRun...", wfRunId);
    WorkflowRunEntity wfRunEntity = this.workflowRunRepository.findById(wfRunId).orElse(null);
    if (wfRunEntity == null
        || !List.of(RunPhase.pending, RunPhase.running).contains(wfRunEntity.getPhase())) {
      return;
    }
    LOGGER.info("[{}] Timing out WorkflowRun.", wfRunId);
    long duration = RunTimeouts.executionMillis(wfRunEntity, new Date());

    // Completion Compare-And-Set: exactly one of the racing timers/sweeps wins the move to
    // completed; only the winner cancels tasks and evaluates the auto-retry, so a duplicate
    // timeout can never spawn a duplicate retry.
    if (workflowRunStateHelper.tryComplete(
            wfRunId,
            List.of(RunPhase.pending, RunPhase.running),
            RunStatus.timedout,
            StatusReason.DEADLINE_EXCEEDED,
            statusMessage,
            duration)
        == null) {
      LOGGER.info("[{}] WorkflowRun already completed. Only the timeout winner acts.", wfRunId);
      return;
    }

    // Cancel Running & Pending Tasks
    cancelPendingAndRunningTasks(wfRunEntity);

    // Winner-only auto-retry
    if (!Objects.isNull(wfRunEntity.getRetries())
        && wfRunEntity.getRetries() != -1
        && wfRunEntity.getRetries() != 0) {
      long retryCount = wfRunEntity.getRetryCount() != null ? wfRunEntity.getRetryCount() : 0;
      if (retryCount < wfRunEntity.getRetries()) {
        try {
          // An automatic retry always starts - a queued-but-unstarted retry would stall.
          workflowRunService.retry(wfRunId, true, retryCount + 1);
        } catch (BoomerangException e) {
          // The retry is a new run and the workspace's quotas may have no room for it. The source
          // run is already terminal, so refusing the retry is the whole outcome.
          LOGGER.warn(
              "[{}] Retry refused: {} {}", wfRunId, e.getReason(), Arrays.toString(e.getArgs()));
        }
      }
    }
  }


  // End the TaskRuns of a run just completed - cancelled, timed out, or failed by a watcher reap:
  // running and queued ones are ended, pending ones queued so the admission gate skips them.
  void cancelPendingAndRunningTasks(WorkflowRunEntity wfRunEntity) {
    // Cancel Running & Queued Tasks. The run's TaskRuns are all materialised at start, so the
    // stored set is the graph; the revision is only needed to materialise a task that does not
    // exist yet. When the revision is gone (deleted workflow), wind down what is stored rather
    // than throw after the run is already terminal and orphan its in-flight TaskRuns. The stored
    // set includes the items of a foreach task, which are not graph vertices.
    workflowRevisionRepository
        .findById(wfRunEntity.getWorkflowRevisionRef())
        .ifPresent(revision -> dagUtility.createTaskList(revision, wfRunEntity));
    List<TaskRunEntity> tasks = taskRunRepository.findByWorkflowRunRef(wfRunEntity.getId());

    // Running and queued (claimed but not yet started) tasks get the same treatment: end() ends
    // up completing them as cancelled through the completion Compare-And-Set, which is
    // unfenced by claim identity so it wins outright regardless of who holds the claim. A
    // dispatcher's eventual start/end for a queued task then arrives at an already-completed
    // TaskRun and is rejected by the ordinary phase checks - no claim.seq bookkeeping needed.
    List<TaskRunEntity> inFlightTasks =
        tasks.stream()
            .filter(t -> RunPhase.running.equals(t.getPhase()) || RunPhase.queued.equals(t.getPhase()))
            .toList();
    LOGGER.info("[{}] Ending {} running or queued TaskRuns.", wfRunEntity.getId(), inFlightTasks.size());
    if (inFlightTasks.size() > 0) {
      inFlightTasks.forEach(
          t -> {
            taskExecutionService.end(t.getId());
          });
    }
    // Check pending tasks and queue to force them to skip - will be
    // trapped by queue task before task order is checked
    List<TaskRunEntity> pendingTasks =
        tasks.stream().filter(t -> RunPhase.pending.equals(t.getPhase())).toList();
    LOGGER.info("[{}] Skipping {} pending TaskRuns.", wfRunEntity.getId(), pendingTasks.size());
    if (pendingTasks.size() > 0) {
      pendingTasks.forEach(
          t -> {
            taskExecutionService.queue(t.getId());
          });
    }
  }

}
