package io.boomerang.engine;

import io.boomerang.workflow.QuotaService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.dispatcher.PollWakeups;
import io.boomerang.common.model.WorkflowConcurrency;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.StatusReason;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.GraphProcessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
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

  @Autowired private QuotaService quotaService;

  // The most runs one drain re-admits, so a completion is never held up by a long queue; the
  // sweep and the next completion take the rest.
  private static final int MAX_DRAIN_ADMISSIONS = EngineConstants.SWEEP_PAGE_SIZE;

  // How long a run whose dispatcher reported a failed provisioning attempt waits for its next one.
  @Value("${flow.watcher.provision-retry-ms:60000}")
  private long provisionRetryMillis;


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
        // Two limits, each checked before admission and again after it: the workflow's own
        // concurrency, then its workspace's concurrent runs. A burst of simultaneous admissions
        // cannot each pass the first count, and the recount keeps the oldest runs up to the limit
        // and steps the rest back. A run over either waits in the workspace's one queue.
        WorkflowConcurrency concurrency = wfRevisionEntity.getConcurrency();
        boolean limited = concurrency != null && concurrency.limits();
        if (limited
            && workflowRunStateHelper.countInFlight(wfRunEntity.getWorkflowRef())
                >= concurrency.getLimit()) {
          overflow(wfRunEntity, concurrency);
          return;
        }
        // A child run starts with its parent's task, which already holds a slot: it is counted
        // in the workspace's total and never held by it.
        QuotaService.RunSlots slots =
            TriggerEnum.task.getTrigger().equals(wfRunEntity.getTrigger())
                ? null
                : quotaService.runSlots(wfRunEntity.getWorkflowRef());
        if (slots != null && slots.full()) {
          parkForWorkspace(wfRunId, slots.limit());
          return;
        }
        // Admission Compare-And-Set: notstarted/pending (or a parked run) becomes ready, persisting
        // the resolved params in the same guarded write. A duplicate queue loses and performs no
        // side effects. A run with a workspace to provision takes its deadline from now.
        Date deadline =
            (wfRunEntity.getWorkspace() != null)
                ? RunTimeouts.deadline(new Date(), wfRunEntity.getTimeout())
                : null;
        if (workflowRunStateHelper.tryAdmit(wfRunId, wfRunEntity.getParams(), deadline) == null) {
          LOGGER.info("[{}] WorkflowRun already admitted. Nothing to do.", wfRunId);
          return;
        }
        if (limited
            && !workflowRunStateHelper.holdsSlot(
                wfRunId, wfRunEntity.getWorkflowRef(), concurrency.getLimit())) {
          overflow(wfRunEntity, concurrency);
          return;
        }
        if (slots != null
            && !workflowRunStateHelper.holdsSlot(wfRunId, slots.workflowRefs(), slots.limit())) {
          parkForWorkspace(wfRunId, slots.limit());
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
                  + inFlightRuns(concurrency)
                  + " is reached.",
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
            StatusReason.CONCURRENCY_LIMIT,
            "Waiting for a slot: the workflow's concurrency limit of "
                + inFlightRuns(concurrency)
                + " is reached.")
        != null) {
      LOGGER.info(
          "[{}] Over the workflow's concurrency limit of {}. Parked for a slot.",
          wfRunEntity.getId(),
          concurrency.getLimit());
    }
  }

  // A run over its workspace's concurrent limit waits for a slot. A park that loses its
  // Compare-And-Set (a dispatcher claimed the run meanwhile) leaves the run in flight over the
  // limit, which the next admissions count.
  private void parkForWorkspace(String wfRunId, int limit) {
    if (workflowRunStateHelper.tryPark(
            wfRunId,
            StatusReason.WORKSPACE_CONCURRENCY_LIMIT,
            "Waiting for a slot: the workspace's limit of "
                + limit
                + (limit == 1 ? " concurrent run" : " concurrent runs")
                + " is reached.")
        != null) {
      LOGGER.info(
          "[{}] Over the workspace's limit of {} concurrent runs. Queued for a slot.",
          wfRunId,
          limit);
    }
  }

  private static String inFlightRuns(WorkflowConcurrency concurrency) {
    int limit = concurrency.getLimit();
    return limit + (limit == 1 ? " in-flight run" : " in-flight runs");
  }

  /**
   * Re-drive a parked run through admission: when a run ends, and on the sweep. Admitted with
   * nothing to provision it starts here; with a workspace it is claimable for provisioning like
   * any submit. Still over a limit, it stays parked. Returns whether it is admitted and waiting
   * to start; a run another caller admitted and started first is neither parked nor counted.
   */
  public boolean readmit(String wfRunId) {
    queue(wfRunId);
    WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElse(null);
    if (run == null
        || !RunStatus.ready.equals(run.getStatus())
        || !RunPhase.pending.equals(run.getPhase())) {
      return false;
    }
    if (run.getWorkspace() != null) {
      pollWakeups.runProvisionable();
      return true;
    }
    try {
      start(wfRunId);
    } catch (BoomerangException lostRace) {
      // Several drains may race for one run: another started it between the read and this start.
      if (!BoomerangError.WORKFLOWRUN_INVALID_PHASE.getReason().equals(lostRace.getReason())) {
        throw lostRace;
      }
      LOGGER.debug("[{}] WorkflowRun already started by another caller. Nothing to do.", wfRunId);
    }
    return true;
  }

  /**
   * Start a run admitted with nothing to provision whose start was lost, if it still waits for
   * one. A caller that started it first wins, and this does nothing. A run that cannot start, such
   * as one whose graph cannot be built, ends invalid instead of being tried again on every sweep;
   * a database error leaves it to the next sweep.
   */
  public void startAdmitted(String wfRunId) {
    WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElse(null);
    if (run == null
        || !RunStatus.ready.equals(run.getStatus())
        || !RunPhase.pending.equals(run.getPhase())
        || run.getWorkspace() != null) {
      return;
    }
    LOGGER.info("[{}] Admitted WorkflowRun never started. Starting it.", wfRunId);
    try {
      start(wfRunId);
    } catch (RuntimeException ex) {
      if (ex instanceof BoomerangException lostRace
          && BoomerangError.WORKFLOWRUN_INVALID_PHASE.getReason().equals(lostRace.getReason())) {
        LOGGER.debug("[{}] WorkflowRun already started by another caller. Nothing to do.", wfRunId);
        return;
      }
      // A database that failed for a moment says nothing about the run: try it on the next sweep.
      if (ex instanceof DataAccessException) {
        throw ex;
      }
      // The completion Compare-And-Set from pending: a run started meanwhile is left alone.
      if (workflowRunStateHelper.tryComplete(
              wfRunId,
              List.of(RunPhase.pending),
              RunStatus.invalid,
              StatusReason.WORKFLOW_INVALID,
              "Failed to run workflow: unable to process Workflow and start it.",
              RunTimeouts.executionMillis(run, new Date()))
          == null) {
        LOGGER.info("[{}] WorkflowRun no longer waiting to start. Nothing to end.", wfRunId);
        return;
      }
      LOGGER.warn(
          "[{}] Admitted WorkflowRun cannot start; ended it invalid. Cause: {}",
          wfRunId,
          (ex instanceof BoomerangException boomerang)
              ? boomerang.getReason() + " " + Arrays.toString(boomerang.getArgs())
              : ex.toString());
    }
  }

  /**
   * Admit the runs waiting in the queue the workflow belongs to, oldest first, while slots are
   * free: the queue of its workspace's workflows, or the workflow's own when no workspace limit
   * applies to it. A run its own workflow's limit still holds stays parked, and its workflow is
   * passed over for the rest of the call, so a backlog on one workflow never hides another
   * workflow's run behind it. With every slot of the workspace taken the child runs go on being
   * admitted: the workspace's limit never holds one, only its own workflow's does. A run whose
   * re-admission fails is passed over for the rest of the call and tried again on the next, so
   * the runs behind it are not kept waiting on it. Returns the workflows whose queue this was.
   */
  public Collection<String> drain(String workflowRef) {
    QuotaService.RunSlots slots = quotaService.runSlots(workflowRef);
    List<String> queue = (slots != null) ? slots.workflowRefs() : List.of(workflowRef);
    List<String> candidates = new ArrayList<>(queue);
    Set<String> failed = new HashSet<>();
    int admissions = 0;
    while (admissions < MAX_DRAIN_ADMISSIONS
        && failed.size() < MAX_DRAIN_ADMISSIONS
        && !candidates.isEmpty()) {
      boolean full = slots != null && workflowRunStateHelper.countInFlight(queue) >= slots.limit();
      WorkflowRunEntity parked = workflowRunStateHelper.findOldestParked(candidates, failed, full);
      if (parked == null) {
        break;
      }
      try {
        LOGGER.info("[{}] Re-admitting the run parked longest for a slot.", parked.getId());
        if (readmit(parked.getId())) {
          admissions++;
        } else if (workflowRunStateHelper.isParked(parked.getId())) {
          // Still held, by its own workflow's limit: nothing behind it in that workflow can go.
          candidates.remove(parked.getWorkflowRef());
        }
      } catch (RuntimeException ex) {
        LOGGER.error("[{}] Unable to re-admit the parked run.", parked.getId(), ex);
        failed.add(parked.getId());
      }
    }
    return queue;
  }

  /**
   * End a run that has waited for a slot longer than the queue's age limit, one created at or
   * before the cutoff: cancelled, with the reason and the wait in its message. A run admitted, or
   * ended, since it was read is left alone.
   */
  public void expire(String wfRunId, Date cutoff, long maxAgeHours) {
    WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElse(null);
    if (run == null) {
      return;
    }
    long waitedMinutes = (System.currentTimeMillis() - run.getCreationDate().getTime()) / 60000;
    String statusMessage =
        "Cancelled after waiting "
            + (waitedMinutes / 60)
            + " h "
            + (waitedMinutes % 60)
            + " min for a slot. The limit is "
            + maxAgeHours
            + (maxAgeHours == 1 ? " hour." : " hours.");
    if (workflowRunStateHelper.tryExpireParked(
            wfRunId, cutoff, StatusReason.QUEUE_EXPIRED, statusMessage)
        == null) {
      LOGGER.info("[{}] WorkflowRun no longer parked past the limit. Nothing to expire.", wfRunId);
      return;
    }
    LOGGER.info("[{}] {}", wfRunId, statusMessage);
    // The task runs admission created never ran; they end as a pending cancel ends them.
    cancelPendingAndRunningTasks(run, true);
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
    // A run parked for a slot holds none: its re-admission starts it, never a caller's start.
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
    String statusMessage = "The WorkflowRun was requested to be cancelled.";
    if (RunPhase.running.equals(workflowExecution.getPhase())) {
      // A run whose graph has ended is past cancelling: its finally tasks run on, and a purge is
      // what stops them.
      if (EngineConstants.inFinallyStage(workflowExecution)) {
        LOGGER.info("[{}] WorkflowRun has ended; its finally tasks run on. Nothing to cancel.", wfRunId);
        return;
      }
      // The graph ends cancelled, once, through the graph-end Compare-And-Set; the graph's tasks
      // wind down and the finally tasks run under the cancelled status.
      Optional<List<TaskRunEntity>> finallyTasks =
          taskExecutionService.endGraph(
              workflowExecution, NOT_ENDED, RunStatus.cancelled, null, statusMessage);
      if (finallyTasks.isEmpty()) {
        LOGGER.info("[{}] WorkflowRun already ended. Nothing to cancel.", wfRunId);
        return;
      }
      cancelPendingAndRunningTasks(workflowExecution, false);
      taskExecutionService.queueFinally(finallyTasks.get());
      return;
    }
    // Completion Compare-And-Set: only a run that has not yet completed can be cancelled - a
    // terminal status is never overwritten. A run that never started has nothing to run finally.
    if (workflowRunStateHelper.tryComplete(
            wfRunId,
            List.of(RunPhase.pending, RunPhase.queued),
            RunStatus.cancelled,
            null,
            statusMessage,
            duration)
        == null) {
      LOGGER.info("[{}] WorkflowRun already completed. Nothing to cancel.", wfRunId);
      return;
    }

    // Cancel Running & Pending Tasks
    cancelPendingAndRunningTasks(workflowExecution, true);
  }

  /*
   * End a run for a purge. A run in its finally stage completes now, with the status its graph
   * ended with, and its finally tasks are ended; any other run is cancelled, which runs its
   * finally tasks as a cancel does.
   */
  public void abort(String wfRunId) {
    WorkflowRunEntity workflowExecution =
        workflowRunRepository
            .findById(wfRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    if (!EngineConstants.inFinallyStage(workflowExecution)) {
      cancel(wfRunId);
      return;
    }
    if (workflowRunStateHelper.tryComplete(
            wfRunId,
            List.of(RunPhase.running),
            workflowExecution.getStatus(),
            null,
            "The WorkflowRun was purged while its finally tasks ran.",
            RunTimeouts.executionMillis(workflowExecution, new Date()))
        == null) {
      LOGGER.info("[{}] WorkflowRun already completed. Nothing to abort.", wfRunId);
      return;
    }
    cancelPendingAndRunningTasks(workflowExecution, true);
  }

  // The statuses a running run's graph may be ended from by a cancel or a timeout.
  private static final Set<RunStatus> NOT_ENDED =
      EnumSet.complementOf(EnumSet.copyOf(EngineConstants.TERMINAL_RUN_STATUSES));

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

    // The finally stage past its deadline: the run completes as its graph ended, failed if it had
    // succeeded, and the finally tasks still running are ended. No retry: the run itself did not
    // time out.
    if (EngineConstants.inFinallyStage(wfRunEntity)) {
      boolean succeeded = RunStatus.succeeded.equals(wfRunEntity.getStatus());
      if (workflowRunStateHelper.tryComplete(
              wfRunId,
              List.of(RunPhase.running),
              succeeded ? RunStatus.failed : wfRunEntity.getStatus(),
              succeeded ? StatusReason.FINALLY_FAILED : null,
              "The finally tasks exceeded their timeout.",
              duration)
          == null) {
        LOGGER.info("[{}] WorkflowRun already completed. Only the timeout winner acts.", wfRunId);
        return;
      }
      cancelPendingAndRunningTasks(wfRunEntity, true);
      return;
    }

    // Exactly one of the racing timers/sweeps wins the move: a running run ends its graph timed
    // out, its finally tasks running under that status, and a pending one completes; only the
    // winner cancels tasks and evaluates the auto-retry, so a duplicate timeout can never spawn
    // a duplicate retry.
    Optional<List<TaskRunEntity>> finallyTasks;
    if (RunPhase.running.equals(wfRunEntity.getPhase())) {
      // The sweep marked the status timed out before this; the graph ends from that mark.
      finallyTasks =
          taskExecutionService.endGraph(
              wfRunEntity,
              EnumSet.of(RunStatus.timedout),
              RunStatus.timedout,
              StatusReason.DEADLINE_EXCEEDED,
              statusMessage);
    } else {
      finallyTasks =
          (workflowRunStateHelper.tryComplete(
                      wfRunId,
                      List.of(RunPhase.pending),
                      RunStatus.timedout,
                      StatusReason.DEADLINE_EXCEEDED,
                      statusMessage,
                      duration)
                  != null)
              ? Optional.of(List.of())
              : Optional.empty();
    }
    if (finallyTasks.isEmpty()) {
      LOGGER.info("[{}] WorkflowRun already completed. Only the timeout winner acts.", wfRunId);
      return;
    }

    // Cancel Running & Pending Tasks
    cancelPendingAndRunningTasks(wfRunEntity, false);
    taskExecutionService.queueFinally(finallyTasks.get());

    // Winner-only auto-retry
    if (!Objects.isNull(wfRunEntity.getRetries())
        && wfRunEntity.getRetries() != -1
        && wfRunEntity.getRetries() != 0) {
      long retryCount = wfRunEntity.getRetryCount() != null ? wfRunEntity.getRetryCount() : 0;
      if (retryCount < wfRunEntity.getRetries()) {
        try {
          // An automatic retry always starts - a staged retry would stall. At a concurrency limit
          // it waits in the queue like any run, and its admission starts it.
          WorkflowRun retried = workflowRunService.retry(wfRunId, true, retryCount + 1);
          if (RunStatus.waiting.equals(retried.getStatus())) {
            LOGGER.info(
                "[{}] Retry {} queued for a slot: {}",
                wfRunId,
                retried.getId(),
                retried.getStatusMessage());
          }
        } catch (BoomerangException e) {
          // The retry is a new run and the workspace's monthly quota or queue may have no room for
          // it. The source run is already terminal, so refusing the retry is the whole outcome.
          LOGGER.warn(
              "[{}] Retry refused: {} {}", wfRunId, e.getReason(), Arrays.toString(e.getArgs()));
        }
      }
    }
  }


  /*
   * A dispatcher could not provision a run it claimed. While attempts remain the claim is held, the
   * reason shown on the run, until a short lease ends and the stale-claim sweep releases it for the
   * next attempt; the last attempt fails the run with the reason. A report for a run this
   * dispatcher no longer holds is ignored. Returns whether it was applied.
   */
  public boolean reportProvisionFailure(String workflowRunId, String dispatcherId, String message) {
    WorkflowRunEntity wfRun = workflowRunRepository.findById(workflowRunId).orElse(null);
    if (wfRun == null
        || !RunStatus.ready.equals(wfRun.getStatus())
        || !RunPhase.queued.equals(wfRun.getPhase())
        || wfRun.getClaim() == null
        || !Objects.equals(dispatcherId, wfRun.getClaim().getBy())) {
      return false;
    }
    long attempt = wfRun.getClaim().getSeq();
    String reason = (message == null || message.isBlank()) ? "no reason given" : message.strip();
    reason = reason.length() > 500 ? reason.substring(0, 500) + "…" : reason;
    if (attempt >= EngineConstants.MAX_PROVISION_ATTEMPTS) {
      String failed = "Workspace provisioning failed after " + attempt + " attempts: " + reason;
      if (workflowRunStateHelper.tryFailProvision(workflowRunId, attempt, failed) == null) {
        return false;
      }
      LOGGER.error("[{}] {} Run failed.", workflowRunId, failed);
      cancelPendingAndRunningTasks(wfRun, true);
      return true;
    }
    long retrySeconds = provisionRetryMillis / 1000;
    String retryIn =
        (retrySeconds % 60 == 0)
            ? (retrySeconds / 60) + (retrySeconds == 60 ? " minute" : " minutes")
            : retrySeconds + " s";
    String held =
        "Workspace provisioning failed: " + reason + ". Retrying in " + retryIn + " (attempt " + attempt + " of "
            + EngineConstants.MAX_PROVISION_ATTEMPTS + ").";
    boolean applied =
        workflowRunStateHelper.tryHoldFailedProvision(
            workflowRunId, attempt, held, new Date(System.currentTimeMillis() + provisionRetryMillis));
    if (applied) {
      LOGGER.warn("[{}] {}", workflowRunId, held);
    }
    return applied;
  }

  // End the TaskRuns of a run just ended - cancelled, timed out, or failed by a watcher reap:
  // running and queued ones are ended, pending ones queued so the admission gate skips them. With
  // includingFinally false the finally tasks are left to run, the graph having ended; true ends
  // them too, for a run completed outright.
  void cancelPendingAndRunningTasks(WorkflowRunEntity wfRunEntity, boolean includingFinally) {
    // Cancel Running & Queued Tasks. The run's TaskRuns are all materialised at start, so the
    // stored set is the graph; the revision is only needed to materialise a task that does not
    // exist yet. When the revision is gone (deleted workflow), wind down what is stored rather
    // than throw after the run is already terminal and orphan its in-flight TaskRuns. The stored
    // set includes the items of a foreach task, which are not graph vertices.
    workflowRevisionRepository
        .findById(wfRunEntity.getWorkflowRevisionRef())
        .ifPresent(revision -> dagUtility.createTaskList(revision, wfRunEntity));
    List<TaskRunEntity> tasks = taskRunRepository.findByWorkflowRunRef(wfRunEntity.getId());
    // The finally tasks and the items of a finally for-each task, which carry no dependencies.
    Set<String> finallyIds =
        tasks.stream().filter(DAGUtility::isFinally).map(TaskRunEntity::getId).collect(Collectors.toSet());

    // Running and queued (claimed but not yet started) tasks get the same treatment: end() ends
    // up completing them as cancelled through the completion Compare-And-Set, which is
    // unfenced by claim identity so it wins outright regardless of who holds the claim. A
    // dispatcher's eventual start/end for a queued task then arrives at an already-completed
    // TaskRun and is rejected by the ordinary phase checks - no claim.seq bookkeeping needed.
    List<TaskRunEntity> inFlightTasks =
        tasks.stream()
            .filter(t -> includingFinally || !(finallyIds.contains(t.getId()) || finallyIds.contains(t.getParentRef())))
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
        tasks.stream()
            .filter(t -> includingFinally || !(finallyIds.contains(t.getId()) || finallyIds.contains(t.getParentRef())))
            .filter(t -> RunPhase.pending.equals(t.getPhase()))
            .toList();
    LOGGER.info("[{}] Skipping {} pending TaskRuns.", wfRunEntity.getId(), pendingTasks.size());
    if (pendingTasks.size() > 0) {
      pendingTasks.forEach(
          t -> {
            taskExecutionService.queue(t.getId());
          });
    }
  }

}
