package io.boomerang.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.boomerang.client.WorkflowClient;
import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.*;
import io.boomerang.common.model.*;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.util.ParameterUtil;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service to manage the execution of tasks
 *
 * <p>Note: Left to the original autowired implementation so as not to break anything
 */
@Service
public class TaskExecutionService {

  private static final Logger LOGGER = LogManager.getLogger(TaskExecutionService.class);

  @Autowired @Lazy private LockManager lockManager;

  @Autowired private DAGUtility dagUtility;

  @Autowired private WorkflowRunRepository workflowRunRepository;

  @Autowired private WorkflowRunService workflowRunService;

  @Autowired private WorkflowService workflowService;

  @Autowired private TaskRunRepository taskRunRepository;

  @Autowired private ActionRepository actionRepository;

  @Autowired private WorkflowClient workflowClient;

  @Autowired private ParameterManager paramManager;

  // Proxy to self so internal hand-offs go through the @Async proxy and hop threads.
  @Autowired @Lazy private TaskExecutionService self;

  @Autowired private JobScheduler jobScheduler;

  @Autowired
  @Lazy
  @Qualifier("asyncTaskExecutor")
  TaskExecutor asyncTaskExecutor;

  /*
   * Callers pass TaskRun ids only; the method re-reads the document at entry so every transition
   * acts on fresh state. Any new information a caller holds must be persisted before handing off
   * the id.
   */
  @Async("asyncTaskExecutor")
  public void queue(String taskRunId) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    TaskRunEntity taskExecution = taskRunRepository.findById(taskRunId).orElse(null);
    if (taskExecution == null) {
      LOGGER.error("[{}] Unable to find TaskRun to queue.", taskRunId);
      return;
    }
    String taskExecutionId = taskExecution.getId();
    LOGGER.info("[{}] Recieved queue task request: {}", taskExecutionId, taskExecution.getName());

    // Check if TaskRun Phase is valid
    if (!RunPhase.pending.equals(taskExecution.getPhase())) {
      LOGGER.error("[{}] Task Status invalid. Cannot queue task.", taskExecutionId);
      return;
    }

    // Check if WorkflowRun Phase is valid
    Optional<WorkflowRunEntity> wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    if (!wfRunEntity.isPresent()) {
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.cancelled,
          RunPhase.completed,
          Optional.of("Unable to find WorkflowRun"));
      return;
    } else if (RunPhase.completed.equals(wfRunEntity.get().getPhase())
        || RunPhase.finalized.equals(wfRunEntity.get().getPhase())) {
      // Set duration. If in Queued. There will be no start time.
      long duration =
          taskExecution.getStartTime() != null
              ? new Date().getTime() - taskExecution.getStartTime().getTime()
              : 0;
      taskExecution.setDuration(duration);
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.skipped,
          RunPhase.completed,
          Optional.of(
              "[{}] WorkflowRun has been marked as {}. TaskRun was never queued, setting TaskRun as Skipped."),
          wfRunEntity.get().getId(),
          wfRunEntity.get().getStatus());
      return;
    }

    // Ensure Task is valid as part of Graph
    List<TaskRunEntity> tasks = dagUtility.retrieveTaskList(wfRunEntity.get().getId());
    boolean canRunTask = dagUtility.canRunTask(tasks, taskExecution);
    LOGGER.debug("[{}] Can run task? {}", taskExecutionId, canRunTask);

    if (canRunTask) {
      // Resolve Parameter Substitutions
      paramManager.resolveParamLayers(wfRunEntity.get(), Optional.of(taskExecution));

      // Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved
      // params in the same guarded write. A duplicate queue of the same TaskRun (e.g. a join
      // queued by both parents) loses here and performs no side effects.
      if (taskRunRepository.tryAdmit(taskExecutionId, taskExecution.getParams()) == null) {
        LOGGER.info("[{}] TaskRun already admitted. Nothing to do.", taskExecutionId);
        return;
      }

      // Auto start System related tasks skipping the start checks
      if (!TaskType.template.equals(taskExecution.getType())
          && !TaskType.script.equals(taskExecution.getType())
          && !TaskType.custom.equals(taskExecution.getType())
          && !TaskType.generic.equals(taskExecution.getType())) {
        LOGGER.debug("[{}] Moving task to Executing: {}", taskExecutionId, taskExecution.getName());
        self.execute(taskExecutionId);
      }
    } else {
      LOGGER.debug("[{}] Skipping task: {}", taskExecutionId, taskExecution.getName());
      // Persist the skipped status for end() to re-read.
      taskExecution.setStatus(RunStatus.skipped);
      taskRunRepository.save(taskExecution);
      self.end(taskExecutionId);
    }
  }

  /*
   * Execute the Start of a task as requested by the Handler or System
   *
   * Note: This is synchronous such that a task is moved into the correct status before a response
   * is provided as part of the API. If the API returned immediately before these checks occur, an
   * integration may believe the task has started and therefore be able to run and end the task
   * prior to an asynchronous version of this method actually completing.
   */
  public void start(String taskRunId) {
    start(taskRunId, Optional.empty(), Optional.empty());
  }

  /*
   * Start with claimant identity. The execution-entry Compare-And-Set in execute() makes a
   * duplicate start harmless; fencing rejects a start carrying a superseded claim.
   */
  public void start(String taskRunId, Optional<String> claimedBy, Optional<Long> claimSeq) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    TaskRunEntity taskExecution = taskRunRepository.findById(taskRunId).orElse(null);
    if (taskExecution == null) {
      LOGGER.error("[{}] Unable to find TaskRun to start.", taskRunId);
      return;
    }
    String taskExecutionId = taskExecution.getId();
    LOGGER.info("[{}] Recieved start task request.", taskExecutionId);

    // Check if Phase is valid.
    // Pending / Queued means it correctly came from queueTask() or Agent;
    if (!RunPhase.pending.equals(taskExecution.getPhase())
        && !RunPhase.queued.equals(taskExecution.getPhase())) {
      LOGGER.debug("[{}] Task Phase / Status invalid.", taskExecutionId);
      return;
    }

    if (!claimantIsValid(taskExecution, claimedBy, claimSeq)) {
      return;
    }

    // Check if WorkflowRun Phase is valid
    Optional<WorkflowRunEntity> wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    if (!wfRunEntity.isPresent()) {
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.cancelled,
          RunPhase.completed,
          Optional.of("Unable to find WorkflowRun"));
      return;
    } else if (RunPhase.completed.equals(wfRunEntity.get().getPhase())
        || RunPhase.finalized.equals(wfRunEntity.get().getPhase())) {
      // Set duration. If in Queued. There will be no start time.
      long duration =
          taskExecution.getStartTime() != null
              ? new Date().getTime() - taskExecution.getStartTime().getTime()
              : 0;
      taskExecution.setDuration(duration);
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.cancelled,
          RunPhase.completed,
          Optional.of(
              "[{}] WorkflowRun has been marked as {}. Setting TaskRun as Cancelled. TaskRun may still run to completion."),
          wfRunEntity.get().getId(),
          wfRunEntity.get().getStatus());
      return;
    } else if (hasWorkflowRunExceededTimeout(wfRunEntity.get())) {
      // Checking WorkflowRun Timeout
      // prior to starting the TaskRun before further execution can happen
      // Timeout will mark the task as skipped.
      workflowRunService.timeout(wfRunEntity.get().getId(), false);
      return;
    }

    self.execute(taskExecutionId);
  }

  /*
   * Executes the Specific TaskType. Method called from queue or start asynchronously
   */
  @Async("asyncTaskExecutor")
  public void execute(String taskRunId) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    TaskRunEntity taskExecution = taskRunRepository.findById(taskRunId).orElse(null);
    if (taskExecution == null) {
      LOGGER.error("[{}] Unable to find TaskRun to execute.", taskRunId);
      return;
    }
    WorkflowRunEntity wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef()).orElse(null);
    if (wfRunEntity == null) {
      LOGGER.error("[{}] Unable to find WorkflowRun to execute TaskRun against.", taskRunId);
      return;
    }
    String taskExecutionId = taskExecution.getId();
    boolean endTask = false;
    TaskType taskType = taskExecution.getType();
    String wfRunId = wfRunEntity.getId();
    LOGGER.info("[{}] Recieved Execute task request for type: {}.", taskExecutionId, taskType);

    // Execution-entry Compare-And-Set: ready becomes running exactly once. A duplicate
    // dispatch of the same TaskRun loses here and performs no side effects.
    taskExecution = taskRunRepository.tryStartExecution(taskExecutionId, new Date());
    if (taskExecution == null) {
      LOGGER.info("[{}] TaskRun already executing or completed. Nothing to do.", taskExecutionId);
      return;
    }

    // If new TaskTypes are added, the following code needs updated as well as the IF statement at
    // the end of QUEUE
    // TaskRunEntities are typically only updated and then passed to end
    // If not ending, then they may save a waiting status.
    switch (taskType) {
      case template, script, custom, generic -> {
        // Nothing to do here. These types wait for a Handler.
        getTaskWorkspaces(taskExecution, wfRunEntity);
      }
      case decision -> {
        processDecision(taskExecution, wfRunId);
        taskExecution.setStatus(RunStatus.succeeded);
        endTask = true;
      }
      case acquirelock -> {
        this.acquireTaskLock(taskExecution, wfRunEntity);
        endTask = true;
      }
      case releaselock -> {
        this.releaseTaskLock(taskExecution, wfRunEntity);
        endTask = true;
      }
      case runworkflow -> {
        this.runWorkflow(taskExecution, wfRunEntity);
        endTask = true;
      }
      case runscheduledworkflow -> {
        this.runScheduledWorkflow(taskExecution, wfRunEntity);
        endTask = true;
      }
      case setwfstatus -> {
        this.saveWorkflowStatus(taskExecution, wfRunEntity);
        taskExecution.setStatus(RunStatus.succeeded);
        endTask = true;
      }
      case setwfproperty -> {
        this.saveWorkflowParam(taskExecution, wfRunEntity);
        taskExecution.setStatus(RunStatus.succeeded);
        endTask = true;
      }
      case approval -> {
        // Task will wait for user action and does not end.
        this.createActionTask(taskExecution, wfRunEntity, ActionType.approval);
      }
      case manual -> {
        // Task will wait for user action and does not end.
        this.createActionTask(taskExecution, wfRunEntity, ActionType.manual);
      }
      case eventwait -> {
        // Task will wait for event and does not end unless preapproved.
        endTask = this.processWaitForEventTask(taskExecution);
        LOGGER.debug("[{}] TaskRun set to end? {}", taskExecution.getId(), endTask);
      }
      case sleep -> {
        this.createSleepTask(taskExecution);
        endTask = true;
      }
      case end, start -> throw new UnsupportedOperationException("Unimplemented case: " + taskType);
      default -> throw new BoomerangException(BoomerangError.TASKRUN_INVALID_TYPE, taskType);
    }

    // Check if task has a timeout set and task is not auto ending
    // If set, create Timeout Delayed CompletableFuture
    // TODO migrate to a scheduled task rather than using Future so that it works across horizontal
    // scaling
    if (endTask) {
      // Persist the type-specific outcome (status, results, decision value) for end() to re-read.
      taskRunRepository.save(taskExecution);
      self.end(taskExecutionId);
    } else if (!Objects.isNull(taskExecution.getTimeout()) && taskExecution.getTimeout() != 0) {
      LOGGER.debug(
          "[{}] TaskRun Timeout provided of {} minutes. Creating future timeout check.",
          taskExecution.getId(),
          taskExecution.getTimeout());
      CompletableFuture.supplyAsync(
          timeoutTaskAsync(taskExecution.getId()),
          CompletableFuture.delayedExecutor(
              taskExecution.getTimeout(), TimeUnit.MINUTES, asyncTaskExecutor));
    }
  }

  /*
   * Execute the End of a task as requested by the Handler
   */
  @Async("asyncTaskExecutor")
  public void end(String taskRunId) {
    end(taskRunId, Optional.empty(), Optional.empty());
  }

  /*
   * End with claimant identity. The completion Compare-And-Set admits exactly one winner - a
   * terminal status can never be overwritten - and only the winner advances the graph. A loser
   * (duplicate end, or an end racing a timeout) logs and returns.
   */
  public void end(String taskRunId, Optional<String> claimedBy, Optional<Long> claimSeq) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    TaskRunEntity taskExecution = taskRunRepository.findById(taskRunId).orElse(null);
    if (taskExecution == null) {
      LOGGER.error("[{}] Unable to find TaskRun to end.", taskRunId);
      return;
    }
    String taskExecutionId = taskExecution.getId();
    LOGGER.info("[{}] Recieved end task request.", taskExecutionId);

    // Check if task has been previously completed or cancelled
    if (RunPhase.completed.equals(taskExecution.getPhase())) {
      LOGGER.error("[{}] Task has already been completed or cancelled.", taskExecutionId);
      return;
    }

    if (!claimantIsValid(taskExecution, claimedBy, claimSeq)) {
      return;
    }

    // Set duration. If in Queued. There will be no start time.
    long duration =
        taskExecution.getStartTime() != null
            ? new Date().getTime() - taskExecution.getStartTime().getTime()
            : 0;

    // Check if WorkflowRun Phase is valid
    Optional<WorkflowRunEntity> optWfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    if (!optWfRunEntity.isPresent()) {
      taskRunRepository.tryComplete(
          taskExecutionId,
          Optional.of(RunStatus.cancelled),
          Optional.of("Unable to find WorkflowRun"),
          duration,
          claimedBy,
          claimSeq);
      return;
    }
    WorkflowRunEntity wfRunEntity = optWfRunEntity.get();
    if (RunPhase.completed.equals(wfRunEntity.getPhase())
        || RunPhase.finalized.equals(wfRunEntity.getPhase())) {
      String statusMessage =
          MessageFormatter.arrayFormat(
                  "[{}] WorkflowRun has been marked as {}. Setting TaskRun as Cancelled. TaskRun may still run to completion.",
                  new Object[] {wfRunEntity.getId(), wfRunEntity.getStatus()})
              .getMessage();
      taskRunRepository.tryComplete(
          taskExecutionId,
          Optional.of(RunStatus.cancelled),
          Optional.of(statusMessage),
          duration,
          claimedBy,
          claimSeq);
      return;
    }

    // The TaskRun timed out when the caller marked it so, or its wall clock ran over the budget.
    boolean taskTimedOut =
        RunStatus.timedout.equals(taskExecution.getStatus())
            || hasTaskRunExceededTimeout(taskExecution);

    // Completion Compare-And-Set: the caller-persisted terminal status stands unless the task
    // timed out. Losing means another end already completed this TaskRun.
    TaskRunEntity preImage =
        taskRunRepository.tryComplete(
            taskExecutionId,
            (taskTimedOut ? Optional.of(RunStatus.timedout) : Optional.empty()),
            (taskTimedOut
                ? Optional.of(
                    MessageFormatter.format(
                            "The TaskRun exceeded the timeout. Timeout was set to {} minutes",
                            taskExecution.getTimeout())
                        .getMessage())
                : Optional.empty()),
            duration,
            claimedBy,
            claimSeq);
    if (preImage == null) {
      LOGGER.info(
          "[{}] TaskRun already completed. Only the completion winner advances.", taskExecutionId);
      return;
    }
    LOGGER.info(
        "[{}] Marking Task as {}.",
        taskExecutionId,
        (taskTimedOut ? RunStatus.timedout : taskExecution.getStatus()));

    // Winner-only follow-on: no further graph advance can happen once a run has run over.
    if (hasWorkflowRunExceededTimeout(wfRunEntity)) {
      // This will update the Workflow status and then call back to this method to be trapped by
      // above WorkflowRun checks
      workflowRunService.timeout(wfRunEntity.getId(), false);
      return;
    } else if (taskTimedOut) {
      workflowRunService.timeout(wfRunEntity.getId(), true);
      return;
    }

    // Winner-only graph advance. The workflow-level lock serialises concurrent advances of the
    // same run (two different tasks completing together) until those writes are field-scoped.
    LOGGER.info(
        "[{}] Attempting to get WorkflowRun ({}) lock", taskExecutionId, wfRunEntity.getId());
    String tokenId = lockManager.acquireLock(wfRunEntity.getId());
    LOGGER.info("[{}] Obtained WorkflowRun ({}) lock", taskExecutionId, wfRunEntity.getId());

    List<TaskRunEntity> tasks = dagUtility.retrieveTaskList(wfRunEntity.getId());
    boolean finishedAllDependencies = this.finishedAll(wfRunEntity, tasks, taskExecution);
    LOGGER.debug("[{}] Finished all TaskRuns? {}", taskExecutionId, finishedAllDependencies);

    // Refresh wfRunEntity and update approval status
    wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef()).orElse(wfRunEntity);
    updatePendingApprovalStatus(wfRunEntity);

    executeNextStep(wfRunEntity, tasks, taskExecution, finishedAllDependencies);

    lockManager.releaseLock(wfRunEntity.getId(), tokenId);
    LOGGER.info("[{}] Released WorkflowRun ({}) lock", taskExecutionId, wfRunEntity.getId());
  }

  /*
   * Fencing: a request carrying claimant identity must match the current claim; a request with
   * no identity is the legacy protocol and is accepted with a log line.
   */
  private boolean claimantIsValid(
      TaskRunEntity taskExecution, Optional<String> claimedBy, Optional<Long> claimSeq) {
    if (claimedBy.isEmpty() && claimSeq.isEmpty()) {
      if (taskExecution.getClaim() != null && taskExecution.getClaim().getBy() != null) {
        LOGGER.info(
            "[{}] Request carries no claimant identity for a claimed TaskRun. Accepting as legacy protocol.",
            taskExecution.getId());
      }
      return true;
    }
    boolean valid =
        taskExecution.getClaim() != null
            && claimedBy.map(by -> by.equals(taskExecution.getClaim().getBy())).orElse(true)
            && claimSeq
                .map(seq -> seq.equals(taskExecution.getClaim().getSeq()))
                .orElse(true);
    if (!valid) {
      LOGGER.error(
          "[{}] Rejecting request from superseded claimant {} (seq {}). Current claim is {} (seq {}).",
          taskExecution.getId(),
          claimedBy.orElse(null),
          claimSeq.orElse(null),
          (taskExecution.getClaim() != null ? taskExecution.getClaim().getBy() : null),
          (taskExecution.getClaim() != null ? taskExecution.getClaim().getSeq() : null));
    }
    return valid;
  }

  /*
   * An async method to execute Timeout checks with DelayedExecutor
   *
   * The CompletableFuture.orTimeout() method can't be used as the TaskRun Async thread will finish
   * and hand over to the Handler and wait for callback.
   *
   * TODO: save error block TODO: implement via quartz Note: Implements same locks as
   * TaskExecutionService
   */
  private Supplier<Boolean> timeoutTaskAsync(String taskRunId) {
    return () -> {
      final Optional<TaskRunEntity> optTaskExecution = this.taskRunRepository.findById(taskRunId);
      if (optTaskExecution.isPresent()) {
        TaskRunEntity taskExecution = optTaskExecution.get();
        // Only need to check if Running - otherwise nothing to timeout
        if (RunPhase.running.equals(taskExecution.getPhase())) {
          LOGGER.info("[{}] Timeout Task Async...", taskRunId);
          // Persist the timedout status for end() to re-read.
          taskExecution.setStatus(RunStatus.timedout);
          taskRunRepository.save(taskExecution);
          self.end(taskRunId);
        }
      }
      return true;
    };
  }

  /*
   * This will approve a task to run
   *
   * TODO: confirm this works
   */
  public List<String> updateTaskRunForTopic(String workflowRunId, String topic) {
    List<String> ids = new LinkedList<>();

    LOGGER.info("[{}] Finding taskRunId based on topic.", workflowRunId);
    List<TaskRunEntity> taskRunEntities =
        this.taskRunRepository.findByWorkflowRunRef(workflowRunId);

    for (TaskRunEntity taskRun : taskRunEntities) {
      if (TaskType.eventwait.equals(taskRun.getType())) {
        List<RunParam> params = taskRun.getParams();
        if (params != null && ParameterUtil.containsName(params, "topic")) {
          // TODO: bring back parameter layering
          // String paramTopic = params.get("topic").toString();
          // ControllerRequestProperties properties = propertyManager
          // .buildRequestPropertyLayering(null, taskRunId, activity.getWorkflowId());
          // topic = propertyManager.replaceValueWithProperty(paramTopic, taskRunId, properties);
          // String taskId = task.getId();
          LOGGER.info("[{}] Found task run id: {} ", workflowRunId, taskRun.getId());
          taskRun.setPreApproved(true);
          this.taskRunRepository.save(taskRun);
          ids.add(taskRun.getId());
        }
      }
    }

    // TODO: figure out what to return
    LOGGER.info("[{}] No task activity ids found for topic: {}", workflowRunId, topic);
    return ids;
  }

  private void getTaskWorkspaces(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    ObjectMapper mapper = new ObjectMapper();
    List<TaskWorkspace> taskWorkspaces = new LinkedList<>();
    wfRunEntity
        .getWorkspaces()
        .forEach(
            ws -> {
              TaskWorkspace tw = new TaskWorkspace();
              WorkflowWorkspaceSpec spec =
                  mapper.convertValue(ws.getSpec(), WorkflowWorkspaceSpec.class);
              tw.setName(ws.getName());
              tw.setMountPath(spec.getMountPath());
              tw.setOptional(ws.isOptional());
              tw.setType(ws.getType());
            });
    taskExecution.setWorkspaces(taskWorkspaces);
  }

  private void updatePendingApprovalStatus(WorkflowRunEntity wfRunEntity) {
    long count =
        actionRepository.countByWorkflowRunRefAndStatus(
            wfRunEntity.getId(), ActionStatus.submitted);
    boolean existingApprovals = (count > 0);
    wfRunEntity.setAwaitingApproval(existingApprovals);
    // Field-scoped write so a level-triggered recompute can never stomp concurrent run state.
    this.workflowRunRepository.setAwaitingApproval(wfRunEntity.getId(), existingApprovals);
  }

  private boolean hasWorkflowRunExceededTimeout(WorkflowRunEntity wfRunEntity) {
    if (!Objects.isNull(wfRunEntity.getTimeout()) && wfRunEntity.getTimeout() != 0) {
      long duration = new Date().getTime() - wfRunEntity.getStartTime().getTime();
      long timeout = TimeUnit.MINUTES.toMillis(wfRunEntity.getTimeout());
      if (duration >= timeout) {
        return true;
      }
    }
    return false;
  }

  private boolean hasTaskRunExceededTimeout(TaskRunEntity taskRunEntity) {
    if (!Objects.isNull(taskRunEntity.getTimeout()) && taskRunEntity.getTimeout() != 0) {
      long duration = new Date().getTime() - taskRunEntity.getStartTime().getTime();
      long timeout = TimeUnit.MINUTES.toMillis(taskRunEntity.getTimeout());
      if (duration >= timeout) {
        return true;
      }
    }
    return false;
  }

  private void saveWorkflowStatus(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    String status = ParameterUtil.getValue(taskExecution.getParams(), "status").toString();
    if (!status.isBlank()) {
      RunStatus taskStatus = RunStatus.valueOf(status);
      wfRunEntity.setStatusOverride(taskStatus);
      this.workflowRunRepository.save(wfRunEntity);
    }
  }

  private void createSleepTask(TaskRunEntity taskExecution) {
    String value = ParameterUtil.getValue(taskExecution.getParams(), "duration").toString();
    long duration = Long.parseLong(value);

    //    jobScheduler.schedule(Instant.now().plus(duration, ChronoUnit.MILLIS), () ->
    // timeoutWorkflowAsync(wfRunEntity.getId()));
    try {
      Thread.sleep(duration);
      taskExecution.setStatus(RunStatus.succeeded);
    } catch (InterruptedException e) {
      taskExecution.setStatus(RunStatus.failed);
      taskExecution.setStatusMessage(e.getMessage());
    }
  }

  private void processDecision(TaskRunEntity taskExecution, String activityId) {
    String decisionValue = ParameterUtil.getValue(taskExecution.getParams(), "value").toString();
    String value = decisionValue;
    taskExecution.setDecisionValue(value);
    taskExecution.setStatus(RunStatus.succeeded);
  }

  private void acquireTaskLock(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    Long timeout = null;
    String key = null;

    List<RunParam> params = taskExecution.getParams();
    if (ParameterUtil.containsName(params, "timeout")) {
      String timeoutStr = ParameterUtil.getValue(params, "timeout").toString();
      if (!timeoutStr.isBlank() && NumberUtils.isCreatable(timeoutStr)) {
        timeout = Long.valueOf(timeoutStr);
      }
    }

    if (ParameterUtil.containsName(params, "key")) {
      key = ParameterUtil.getValue(params, "key").toString();
    }

    // Set team prefix if available from Workflow to scope
    if (taskExecution.getAnnotations() != null
        && !taskExecution.getAnnotations().isEmpty()
        && taskExecution.getAnnotations().containsKey("boomerang.io/team-name")) {
      key = taskExecution.getAnnotations().get("boomerang.io/team-name").toString() + "-" + key;
    }

    try {
      if (Objects.isNull(key) || Objects.isNull(timeout)) {
        throw new BoomerangException(BoomerangError.TASKRUN_INVALID_PARAMS);
      }
      LOGGER.debug("[{}] Acquiring lock for key: {}", taskExecution.getId(), key);
      lockManager.acquireLock(key, timeout);
    } catch (Exception ex) {
      taskExecution.setStatus(RunStatus.failed);
      taskExecution.setStatusMessage(ex.getMessage());
    }
    taskExecution.setStatus(RunStatus.succeeded);
  }

  private void releaseTaskLock(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    String key = null;

    List<RunParam> params = taskExecution.getParams();
    if (ParameterUtil.containsName(params, "key")) {
      key = ParameterUtil.getValue(params, "key").toString();
    }

    // Set team prefix if available from Workflow to scope
    if (taskExecution.getAnnotations() != null
        && !taskExecution.getAnnotations().isEmpty()
        && taskExecution.getAnnotations().containsKey("boomerang.io/team-name")) {
      key = taskExecution.getAnnotations().get("boomerang.io/team-name").toString() + "-" + key;
    }
    try {
      if (Objects.isNull(key)) {
        throw new BoomerangException(BoomerangError.TASKRUN_INVALID_PARAMS);
      }
      LOGGER.debug("[{}] Releasing lock for key: {}", taskExecution.getId(), key);
      lockManager.releaseLock(key, key);
    } catch (Exception ex) {
      taskExecution.setStatus(RunStatus.failed);
      taskExecution.setStatusMessage(ex.getMessage());
    }
    taskExecution.setStatus(RunStatus.succeeded);
  }

  private void runWorkflow(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    LOGGER.debug("[{}] RunWorkflow Request received.", taskExecution.getId());
    if (taskExecution.getParams() != null
        && ParameterUtil.containsName(taskExecution.getParams(), "workflowRef")) {
      try {
        String workflowRef =
            ParameterUtil.getValue(taskExecution.getParams(), "workflowRef").toString();
        WorkflowSubmitRequest request = new WorkflowSubmitRequest();
        request.setTrigger(TriggerEnum.task);
        request.setParams(wfRunEntity.getParams());
        request.setAnnotations(wfRunEntity.getAnnotations());
        request.setWorkspaces(wfRunEntity.getWorkspaces());
        request.setTimeout(wfRunEntity.getTimeout());
        request.setRetries(wfRunEntity.getRetries());
        request.setLabels(wfRunEntity.getLabels());
        request.setDebug(wfRunEntity.getDebug());
        // TODO figure out how to set version
        //        request.setWorkflowVersion();
        LOGGER.debug(
            "[{}] Submitting RunWorkflow Request for ref: {}.", taskExecution.getId(), workflowRef);
        WorkflowRun wfRunResponse = workflowService.submit(workflowRef, request, false);
        //        WorkflowRun wfRunResponse =
        //            workflowClient.submitWorkflow(workflowRef, request, Optional.of(false));
        workflowClient.createWorkflowRunRelationship(workflowRef, wfRunResponse.getId());
        List<RunResult> wfRunResultResponse = new LinkedList<>();
        RunResult runResult = new RunResult();
        runResult.setName("workflowRunRef");
        runResult.setValue(wfRunResponse.getId());
        wfRunResultResponse.add(runResult);
        taskExecution.setResults(wfRunResultResponse);
        taskExecution.setStatus(RunStatus.succeeded);
      } catch (Exception ex) {
        LOGGER.error(
            "[{}] Unable to execute RunWorkflow task. Error: {}",
            taskExecution.getId(),
            ex.getMessage());
        taskExecution.setStatusMessage(ex.getMessage());
        taskExecution.setStatus(RunStatus.failed);
      }
    }
    taskRunRepository.save(taskExecution);
  }

  private void runScheduledWorkflow(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    if (taskExecution.getParams() != null) {
      String workflowId =
          ParameterUtil.getValue(taskExecution.getParams(), "workflowRef").toString();
      Integer futureIn =
          Integer.valueOf(ParameterUtil.getValue(taskExecution.getParams(), "futureIn").toString());
      String futurePeriod =
          ParameterUtil.getValue(taskExecution.getParams(), "futurePeriod").toString();
      String timezone = ParameterUtil.getValue(taskExecution.getParams(), "timezone").toString();
      String time = ParameterUtil.getValue(taskExecution.getParams(), "time").toString();
      Date executionDate = taskExecution.getCreationDate();
      LOGGER.debug("*******Run Scheduled Workflow System Task******");
      LOGGER.debug("Scheduling new task in " + futureIn + " " + futurePeriod);

      if (Objects.nonNull(futureIn)
          && futureIn != 0
          && StringUtils.indexOfAny(
                  futurePeriod, new String[] {"minutes", "hours", "days", "weeks", "months"})
              >= 0) {
        Calendar executionCal = Calendar.getInstance();
        executionCal.setTime(executionDate);
        Integer calField = Calendar.MINUTE;
        switch (futurePeriod) {
          case "hours":
            calField = Calendar.HOUR;
            break;
          case "days":
            calField = Calendar.DATE;
            break;
          case "weeks":
            futureIn = futureIn * 7;
            calField = Calendar.DATE;
            break;
          case "months":
            calField = Calendar.MONTH;
            break;
        }
        executionCal.add(calField, futureIn);

        if (!futurePeriod.equals("minutes") && !futurePeriod.equals("hours")) {
          String[] hoursTime = time.split(":");
          Integer hours = Integer.valueOf(hoursTime[0]);
          Integer minutes = Integer.valueOf(hoursTime[1]);
          LOGGER.debug("With time to be set to: " + time + " in " + timezone);
          executionCal.setTimeZone(TimeZone.getTimeZone(timezone));
          executionCal.set(Calendar.HOUR, hours);
          executionCal.set(Calendar.MINUTE, minutes);
          LOGGER.debug(
              "With execution set to: " + executionCal.getTime().toString() + " in " + timezone);
          executionCal.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
        LOGGER.debug("With execution set to: " + executionCal.getTime().toString() + " in UTC");

        // Define new properties removing the System Task specific properties
        // TODO - determine if we need to resolve any param layers before executing new workflow
        List<RunParam> newParamList = taskExecution.getParams();
        ParameterUtil.removeEntry(newParamList, "workflowId");
        ParameterUtil.removeEntry(newParamList, "futureIn");
        ParameterUtil.removeEntry(newParamList, "futurePeriod");
        ParameterUtil.removeEntry(newParamList, "timezone");
        ParameterUtil.removeEntry(newParamList, "time");

        // Define and create the schedule
        WorkflowSchedule schedule = new WorkflowSchedule();
        schedule.setWorkflowRef(workflowId);
        schedule.setName(taskExecution.getName());
        schedule.setDescription(
            "This schedule was generated through a Run Scheduled Workflow task.");
        schedule.setParams(newParamList);
        schedule.setDateSchedule(executionCal.getTime());
        schedule.setTimezone(timezone);
        schedule.setType(WorkflowScheduleType.runOnce);
        try {
          WorkflowSchedule workflowSchedule = workflowClient.createSchedule(schedule);
          if (workflowSchedule != null && workflowSchedule.getId() != null) {
            LOGGER.debug("Workflow Scheudle (" + workflowSchedule.getId() + ") created.");
            taskExecution.setStatus(RunStatus.succeeded);
            return;
          }
        } catch (Exception ex) {
          taskExecution.setStatusMessage(ex.getMessage());
          taskExecution.setStatus(RunStatus.failed);
        }
      }
    }
    taskExecution.setStatus(RunStatus.failed);
  }

  private boolean processWaitForEventTask(TaskRunEntity taskExecution) {
    LOGGER.debug(
        "[{}] Processing Wait for Event task: {}", taskExecution.getId(), taskExecution.getName());
    taskExecution.setStatus(RunStatus.waiting);
    taskExecution = taskRunRepository.save(taskExecution);

    if (taskExecution.isPreApproved()) {
      if (taskExecution.getAnnotations().get("boomerang.io/status") != null) {
        taskExecution.setStatus(
            RunStatus.getRunStatus(
                (String) taskExecution.getAnnotations().get("boomerang.io/status")));
      } else {
        taskExecution.setStatus(RunStatus.succeeded);
      }
      LOGGER.debug(
          "[{}]  Wait for Task is already approved, with status: {}.",
          taskExecution.getId(),
          taskExecution.getStatus());
      return true;
    }
    return false;
  }

  /*
   * Creates an Action entity of Manual or Approval type
   *
   * If of Approval type, will check for optional number of approvers and approverGroup
   */
  private void createActionTask(
      TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity, ActionType type) {
    ActionEntity actionEntity = new ActionEntity();
    actionEntity.setTaskRunRef(taskExecution.getId());
    actionEntity.setWorkflowRunRef(wfRunEntity.getId());
    actionEntity.setWorkflowRef(wfRunEntity.getWorkflowRef());
    actionEntity.setStatus(ActionStatus.submitted);
    actionEntity.setType(type);
    actionEntity.setCreationDate(new Date());
    actionEntity.setNumberOfApprovers(1);

    if (taskExecution.getParams() != null) {
      if (type.equals(ActionType.approval)) {
        if (ParameterUtil.containsName(taskExecution.getParams(), "approverGroupId")) {
          String approverGroupId =
              (String) ParameterUtil.getValue(taskExecution.getParams(), "approverGroupId");
          if (approverGroupId != null && !approverGroupId.isBlank()) {
            actionEntity.setApproverGroupRef(approverGroupId);
          }
        }

        if (ParameterUtil.containsName(taskExecution.getParams(), "numberOfApprovers")) {
          String numberOfApprovers =
              (String) ParameterUtil.getValue(taskExecution.getParams(), "numberOfApprovers");
          if (numberOfApprovers != null && !numberOfApprovers.isBlank()) {
            actionEntity.setNumberOfApprovers(Integer.valueOf(numberOfApprovers));
          }
        }
      } else if (type.equals(ActionType.manual)) {
        if (ParameterUtil.containsName(taskExecution.getParams(), "instructions")) {
          String instructions =
              (String) ParameterUtil.getValue(taskExecution.getParams(), "instructions");
          if (instructions != null && !instructions.isBlank()) {
            actionEntity.setInstructions(instructions);
          }
        }
      }
    }
    actionEntity = actionRepository.save(actionEntity);
    taskExecution.getResults().add(new RunResult("actionRef", actionEntity.getId()));
    taskExecution.setStatus(RunStatus.waiting);
    taskExecution = taskRunRepository.save(taskExecution);
    wfRunEntity.setAwaitingApproval(true);
    String tokenId = lockManager.acquireLock(wfRunEntity.getId());
    this.workflowRunRepository.save(wfRunEntity);
    lockManager.releaseLock(wfRunEntity.getId(), tokenId);
  }

  private void saveWorkflowParam(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    String input =
        (String)
            taskExecution.getParams().stream()
                .filter(p -> "value".equals(p.getName()))
                .findFirst()
                .get()
                .getValue();
    String output =
        (String)
            taskExecution.getParams().stream()
                .filter(p -> "output".equals(p.getName()))
                .findFirst()
                .get()
                .getValue();

    String tokenId = lockManager.acquireLock(wfRunEntity.getId());

    List<RunResult> wfResults = wfRunEntity.getResults();
    RunResult wfResult = new RunResult();
    wfResult.setName(output);
    wfResult.setValue(input);
    wfResults.add(wfResult);
    wfRunEntity.setResults(wfResults);
    workflowRunRepository.save(wfRunEntity);

    lockManager.releaseLock(wfRunEntity.getId(), tokenId);
    taskExecution.setStatus(RunStatus.succeeded);
  }

  private void finishWorkflow(WorkflowRunEntity wfRunEntity, List<TaskRunEntity> tasks) {
    // Updates the status of end task
    tasks.stream()
        .filter(t -> TaskType.end.equals(t.getType()))
        .forEach(
            t -> {
              t.setStatus(RunStatus.succeeded);
              t.setPhase(RunPhase.completed);
              taskRunRepository.save(t);
            });
    // Validate all paths have been taken
    // It also updates the status of each task and checks dependencies.
    boolean workflowCompleted = dagUtility.validateWorkflow(wfRunEntity, tasks);

    // Set WorkflowRun status and phase
    if (wfRunEntity.getStatusOverride() != null) {
      wfRunEntity.setStatus(wfRunEntity.getStatusOverride());
    } else {
      if (workflowCompleted) {
        wfRunEntity.setStatus(RunStatus.succeeded);
      } else {
        wfRunEntity.setStatus(RunStatus.failed);
      }
    }
    wfRunEntity.setPhase(RunPhase.completed);

    // Calc Duration
    long duration = new Date().getTime() - wfRunEntity.getStartTime().getTime();
    wfRunEntity.setDuration(duration);

    this.workflowRunRepository.save(wfRunEntity);
    LOGGER.info(
        "[{}] Completed Workflow with status: {}.", wfRunEntity.getId(), wfRunEntity.getStatus());
  }

  private void executeNextStep(
      WorkflowRunEntity wfRunEntity,
      List<TaskRunEntity> tasks,
      TaskRunEntity currentTask,
      boolean finishedAll) {
    List<TaskRunEntity> nextNodes = dagUtility.getTasksDependants(tasks, currentTask);
    LOGGER.debug(
        "[{}] Looking at next tasks. Found {} tasks. Tasks: {}",
        wfRunEntity.getId(),
        nextNodes.size(),
        nextNodes.toString());

    for (TaskRunEntity next : nextNodes) {
      if (TaskType.end.equals(next.getType())) {
        if (finishedAll) {
          LOGGER.debug("FINISHED ALL");
          this.finishWorkflow(wfRunEntity, tasks);
          return;
        }
        continue;
      }

      boolean executeTask = canExecuteTask(wfRunEntity, tasks, next);
      if (executeTask) {
        LOGGER.debug("[{}] Execute next TaskRun: {}", wfRunEntity.getId(), next.getName());
        Optional<TaskRunEntity> taskRunEntity =
            this.taskRunRepository.findById(currentTask.getId());
        if (!taskRunEntity.isPresent()) {
          LOGGER.error("Reached node which should not be executed.");
        } else {
          self.queue(next.getId());
        }
      } else {
        LOGGER.debug(
            "[{}] Unable to execute next TaskRun: {}. Not all dependencies have been completed.",
            wfRunEntity.getId(),
            next.getName());
      }
    }
  }

  /*
   * Checks if all the dependencies for the End task have been completed
   */
  private boolean finishedAll(
      WorkflowRunEntity wfRunEntity, List<TaskRunEntity> tasks, TaskRunEntity currentTask) {
    boolean finishedAll = true;
    List<TaskRunEntity> nextNodes = dagUtility.getTasksDependants(tasks, currentTask);
    LOGGER.debug("[{}] Task Dependencies: {}", currentTask.getId(), nextNodes.toString());
    for (TaskRunEntity next : nextNodes) {
      if (TaskType.end.equals(next.getType())) {
        List<WorkflowTaskDependency> deps = next.getDependencies();
        for (WorkflowTaskDependency dep : deps) {
          Optional<TaskRunEntity> taskRunEntity =
              findTaskByName(tasks, wfRunEntity, dep.getTaskRef());
          if (taskRunEntity.isEmpty()) {
            return false;
          }

          RunPhase phase = taskRunEntity.get().getPhase();
          if (!RunPhase.completed.equals(phase)) {
            finishedAll = false;
            // Performance wise we don't need to finish the looping and can exit
            return finishedAll;
          }
        }
      }
    }

    return finishedAll;
  }

  private boolean canExecuteTask(
      WorkflowRunEntity wfRunEntity, List<TaskRunEntity> tasks, TaskRunEntity next) {
    List<WorkflowTaskDependency> deps = next.getDependencies();
    LOGGER.debug("Found {} dependencies", deps.size());
    for (WorkflowTaskDependency dep : deps) {
      Optional<TaskRunEntity> taskRunEntity = findTaskByName(tasks, wfRunEntity, dep.getTaskRef());
      if (taskRunEntity.isEmpty()) {
        return false;
      }
      RunPhase phase = taskRunEntity.get().getPhase();
      if (!RunPhase.completed.equals(phase)) {
        return false;
      }
    }
    return true;
  }

  /*
   * Dependency lookup over the batched TaskRun fetch. Every DAG node is materialised as a
   * TaskRun at queue time; a missing dependency is a broken invariant and can never count as
   * satisfied.
   */
  private Optional<TaskRunEntity> findTaskByName(
      List<TaskRunEntity> tasks, WorkflowRunEntity wfRunEntity, String name) {
    Optional<TaskRunEntity> taskRunEntity =
        tasks.stream().filter(t -> name.equals(t.getName())).findFirst();
    if (taskRunEntity.isEmpty()) {
      LOGGER.error(
          "[{}] No TaskRun found for dependency: {}. A missing dependency can never be"
              + " treated as satisfied.",
          wfRunEntity.getId(),
          name);
    }
    return taskRunEntity;
  }

  public void updateStatusAndSaveTask(
      TaskRunEntity taskExecution,
      RunStatus status,
      RunPhase phase,
      Optional<String> message,
      Object... messageArgs) {
    if (RunStatus.failed.equals(status) && message.isPresent()) {
      LOGGER.error(MessageFormatter.arrayFormat(message.get(), messageArgs).getMessage());
    } else if (message.isPresent()) {
      taskExecution.setStatusMessage(
          MessageFormatter.arrayFormat(message.get(), messageArgs).getMessage());
    }
    taskExecution.setStatus(status);
    taskExecution.setPhase(phase);
    taskRunRepository.save(taskExecution);
  }
}
