package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.GraphProcessor;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class WorkflowExecutionService {
  private static final Logger LOGGER = LogManager.getLogger(WorkflowExecutionService.class);

  @Autowired private WorkflowRunRepository workflowRunRepository;

  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  @Autowired private DAGUtility dagUtility;

  @Autowired @Lazy private TaskExecutionService taskExecutionService;

  @Autowired private ParameterManager paramManager;

  @Autowired @Lazy private WorkflowRunService workflowRunService;


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
    // Resolve Parameter Substitutions
    // TODO: check if we need this
    paramManager.resolveParamLayers(wfRunEntity, Optional.empty());

    final Optional<WorkflowRevisionEntity> optWorkflowRevisionEntity =
        this.workflowRevisionRepository.findById(wfRunEntity.getWorkflowRevisionRef());
    if (optWorkflowRevisionEntity.isPresent()) {
      WorkflowRevisionEntity wfRevisionEntity = optWorkflowRevisionEntity.get();
      final List<TaskRunEntity> tasks = dagUtility.createTaskList(wfRevisionEntity, wfRunEntity);
      LOGGER.info("[{}] Found {} tasks: {}", wfRunEntity.getId(), tasks.size(), tasks.toString());
      if (dagUtility.validateWorkflow(wfRunEntity, tasks)) {
        // Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved
        // params in the same guarded write. A duplicate queue loses and performs no side effects.
        if (workflowRunService.tryAdmit(wfRunId, wfRunEntity.getParams()) == null) {
          LOGGER.info("[{}] WorkflowRun already admitted. Nothing to do.", wfRunId);
        }
        return;
      }
    }
    updateStatusAndSaveWorkflow(
        wfRunEntity,
        RunStatus.invalid,
        RunPhase.completed,
        Optional.of("Failed to run workflow: incomplete, or invalid, workflow"));
    throw new BoomerangException(
        1000,
        "WORKFLOW_RUNTIME_EXCEPTION",
        "[{0}] Failed to run workflow: incomplete, or invalid, workflow",
        HttpStatus.INTERNAL_SERVER_ERROR,
        wfRunEntity.getId());
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
    final List<TaskRunEntity> tasks = dagUtility.retrieveTaskList(wfRunEntity.getId());
    final TaskRunEntity start = dagUtility.getTaskByType(tasks, TaskType.start);
    final TaskRunEntity end = dagUtility.getTaskByType(tasks, TaskType.end);
    final Graph<String, DefaultEdge> graph = dagUtility.createGraph(tasks);
    return CompletableFuture.supplyAsync(
        executeWorkflowAsync(
            wfRunEntity.getId(), wfRunEntity.getTimeout(), start, end, graph, tasks),
        asyncWorkflowExecutor);
  }

  public void end(String wfRunId) {
    WorkflowRunEntity workflowExecution =
        workflowRunRepository
            .findById(wfRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    // Finalize Compare-And-Set: only a completed run can be finalized, so an early or duplicate
    // finalize can never stomp a run that is still executing.
    if (workflowRunService.tryFinalize(wfRunId) == null) {
      LOGGER.info(
          "[{}] WorkflowRun not completed (phase: {}). Nothing to finalize.",
          wfRunId,
          workflowExecution.getPhase());
    }
  }

  public void cancel(String wfRunId) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    WorkflowRunEntity workflowExecution =
        workflowRunRepository
            .findById(wfRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF));
    long duration = 0;
    if (workflowExecution.getStartTime() != null) {
      duration = new Date().getTime() - workflowExecution.getStartTime().getTime();
    }
    // Completion Compare-And-Set: only a run that has not yet completed can be cancelled - a
    // terminal status is never overwritten.
    if (workflowRunService.tryComplete(
            wfRunId,
            List.of(RunPhase.pending, RunPhase.queued, RunPhase.running),
            RunStatus.cancelled,
            "The WorkflowRun was requested to be cancelled.",
            duration)
        == null) {
      LOGGER.info("[{}] WorkflowRun already completed. Nothing to cancel.", wfRunId);
      return;
    }

    // Cancel Running & Pending Tasks
    cancelPendingAndRunningTasks(workflowExecution);
  }

  @Async("asyncWorkflowExecutor")
  public void timeout(String wfRunId, String statusMessage) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    WorkflowRunEntity workflowExecution = workflowRunRepository.findById(wfRunId).orElse(null);
    if (workflowExecution == null) {
      LOGGER.error("[{}] Unable to find WorkflowRun to timeout.", wfRunId);
      return;
    }
    if (RunStatus.timedout.equals(workflowExecution.getStatus())
        || (!Objects.isNull(workflowExecution.getTimeout())
            && workflowExecution.getTimeout() != 0)) {
      timeoutWorkflow(workflowExecution.getId(), statusMessage);
    }
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
      WorkflowRunEntity wfRunEntity = workflowRunService.tryStart(wfRunId, new Date(), timeout);
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
      } catch (Exception e) {
        updateStatusAndSaveWorkflow(
            wfRunEntity,
            RunStatus.invalid,
            RunPhase.completed,
            Optional.of("Failed to run workflow: unable to process Workflow and queue all tasks."));
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

  /*
   * Times out a running WorkflowRun. Reached from the watcher timeout sweep and the
   * task-timeout path - idempotent against each other via the completion Compare-And-Set.
   */
  public void timeoutWorkflow(String wfRunId, String statusMessage) {
    LOGGER.debug("[{}] Commencing Timeout Workflow Async...", wfRunId);
    WorkflowRunEntity wfRunEntity = this.workflowRunRepository.findById(wfRunId).orElse(null);
    if (wfRunEntity == null || !RunPhase.running.equals(wfRunEntity.getPhase())) {
      return;
    }
    LOGGER.info("[{}] Timeout Workflow Async...", wfRunId);
    long duration =
        wfRunEntity.getStartTime() != null
            ? new Date().getTime() - wfRunEntity.getStartTime().getTime()
            : 0;

    // Completion Compare-And-Set: exactly one of the racing timers/sweeps wins running ->
    // completed; only the winner cancels tasks and evaluates the auto-retry, so a duplicate
    // timeout can never spawn a duplicate retry.
    if (workflowRunService.tryComplete(
            wfRunId, List.of(RunPhase.running), RunStatus.timedout, statusMessage, duration)
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
        retryCount++;
        // An automatic retry always starts - a queued-but-unstarted retry would stall.
        workflowRunService.retry(wfRunId, true, retryCount);
      }
    }
  }

  private void cancelPendingAndRunningTasks(WorkflowRunEntity wfRunEntity) {
    // Cancel Running Tasks
    Optional<WorkflowRevisionEntity> wfRevisionEntity =
        workflowRevisionRepository.findById(wfRunEntity.getWorkflowRevisionRef());
    List<TaskRunEntity> tasks = dagUtility.createTaskList(wfRevisionEntity.get(), wfRunEntity);

    // If running tasks are found, the TaskRun execution loop will automatically cancel in
    // flight tasks when you end them based on workflow status and skip all queued
    List<TaskRunEntity> runningTasks =
        tasks.stream().filter(t -> RunPhase.running.equals(t.getPhase())).toList();
    LOGGER.info("Timeout - # of Running Tasks: " + runningTasks.size());
    if (runningTasks.size() > 0) {
      runningTasks.forEach(
          t -> {
            taskExecutionService.end(t.getId());
          });
    }
    // Check pending tasks and queue to force them to skip - will be
    // trapped by queue task before task order is checked
    List<TaskRunEntity> pendingTasks =
        tasks.stream().filter(t -> RunPhase.pending.equals(t.getPhase())).toList();
    LOGGER.info("Timeout - # of Pending Tasks: " + pendingTasks.size());
    if (pendingTasks.size() > 0) {
      pendingTasks.forEach(
          t -> {
            taskExecutionService.queue(t.getId());
          });
    }
  }

  private void updateStatusAndSaveWorkflow(
      WorkflowRunEntity workflowExecution,
      RunStatus status,
      RunPhase phase,
      Optional<String> message,
      Object... messageArgs) {
    if (message.isPresent()) {
      workflowExecution.setStatusMessage(
          MessageFormatter.arrayFormat(message.get(), messageArgs).getMessage());
    }
    workflowExecution.setStatus(status);
    workflowExecution.setPhase(phase);
    workflowRunRepository.save(workflowExecution);
  }
}
