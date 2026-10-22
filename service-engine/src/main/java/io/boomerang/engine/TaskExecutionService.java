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

  @Autowired private TaskExecutionClient taskExecutionClient;

  @Autowired private JobScheduler jobScheduler;

  @Autowired
  @Lazy
  @Qualifier("asyncTaskExecutor")
  TaskExecutor asyncTaskExecutor;

  @Async("asyncTaskExecutor")
  public void queue(TaskRunEntity taskExecution) {
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

      // Update Status and Phase
      updateStatusAndSaveTask(taskExecution, RunStatus.ready, RunPhase.pending, Optional.empty());

      // Auto start System related tasks skipping the start checks
      if (!TaskType.template.equals(taskExecution.getType())
          && !TaskType.script.equals(taskExecution.getType())
          && !TaskType.custom.equals(taskExecution.getType())
          && !TaskType.generic.equals(taskExecution.getType())) {
        LOGGER.debug("[{}] Moving task to Executing: {}", taskExecutionId, taskExecution.getName());
        taskExecutionClient.execute(this, taskExecution, wfRunEntity.get());
      }
    } else {
      LOGGER.debug("[{}] Skipping task: {}", taskExecutionId, taskExecution.getName());
      taskExecution.setStatus(RunStatus.skipped);
      taskExecutionClient.end(this, taskExecution);
    }
  }

  /*
   * Execute the Start of a task as requested by the Handler or System
   *
   * This needs to get a lock on the TaskRun so that if a Handler makes additional requests on this
   * task, it waits for the end.
   *
   * Note: This is synchronous such that a task is moved into the correct status before a response
   * is provided as part of the API. If the API returned immediately before these checks occur, an
   * integration may believe the task has started and therefore be able to run and end the task
   * prior to an asynchronous version of this method actually completing.
   */
  public void start(TaskRunEntity taskExecution) {
    String taskExecutionId = taskExecution.getId();
    LOGGER.info("[{}] Recieved start task request.", taskExecutionId);

    // Check if Phase is valid.
    // Pending / Queued means it correctly came from queueTask() or Agent;
    if (!RunPhase.pending.equals(taskExecution.getPhase())
        && !RunPhase.queued.equals(taskExecution.getPhase())) {
      LOGGER.debug("[{}] Task Phase / Status invalid.", taskExecutionId);
      return;
    }

    LOGGER.debug("[{}] Attempting to acquire TaskRun lock...", taskExecutionId);
    String lockId = lockManager.acquireLock(taskExecutionId);
    LOGGER.info("[{}] Obtained TaskRun lock", taskExecutionId);

    // Check if WorkflowRun Phase is valid
    Optional<WorkflowRunEntity> wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    if (!wfRunEntity.isPresent()) {
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.cancelled,
          RunPhase.completed,
          Optional.of("Unable to find WorkflowRun"));

      lockManager.releaseLock(taskExecutionId, lockId);
      LOGGER.info("[{}] Released TaskRun lock", taskExecutionId);
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

      lockManager.releaseLock(taskExecutionId, lockId);
      LOGGER.info("[{}] Released TaskRun lock", taskExecutionId);
      return;
    } else if (hasWorkflowRunExceededTimeout(wfRunEntity.get())) {
      lockManager.releaseLock(taskExecutionId, lockId);
      LOGGER.info("[{}] Released TaskRun lock", taskExecutionId);
      // Checking WorkflowRun Timeout
      // prior to starting the TaskRun before further execution can happen
      // Timeout will mark the task as skipped.
      workflowRunService.timeout(wfRunEntity.get().getId(), false);
      return;
    }

    lockManager.releaseLock(taskExecutionId, lockId);
    LOGGER.info("[{}] Released TaskRun lock", taskExecutionId);

    taskExecutionClient.execute(this, taskExecution, wfRunEntity.get());
  }

  /*
   * Executes the Specific TaskType. Method called from queue or start asynchronously
   *
   * No status checks are performed as part of this method. They are handled in start().
   */
  @Async("asyncTaskExecutor")
  public void execute(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    String taskExecutionId = taskExecution.getId();
    boolean endTask = false;
    TaskType taskType = taskExecution.getType();
    String wfRunId = wfRunEntity.getId();
    LOGGER.info("[{}] Recieved Execute task request for type: {}.", taskExecutionId, taskType);

    // Set Task status and start time for duration
    taskExecution.setStartTime(new Date());
    updateStatusAndSaveTask(taskExecution, RunStatus.running, RunPhase.running, Optional.empty());

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
      taskExecutionClient.end(this, taskExecution);
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
   *
   * This needs to get a lock on the TaskRun so that if a Handler requests end, it waits for the
   * task to finish.
   */
  @Async("asyncTaskExecutor")
  public void end(TaskRunEntity taskExecution) {
    String taskExecutionId = taskExecution.getId();
    LOGGER.info("[{}] Recieved end task request.", taskExecutionId);

    // Check if task has been previously completed or cancelled
    if (RunPhase.completed.equals(taskExecution.getPhase())) {
      LOGGER.error("[{}] Task has already been completed or cancelled.", taskExecutionId);
      return;
    }

    LOGGER.debug("[{}] Attempting to acquire TaskRun ({}) lock", taskExecutionId, taskExecutionId);
    String taskTokenId = lockManager.acquireLock(taskExecutionId);
    LOGGER.info("[{}] Obtained TaskRun ({}) lock", taskExecutionId, taskExecutionId);

    // Check if WorkflowRun Phase is valid
    Optional<WorkflowRunEntity> wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    if (!wfRunEntity.isPresent()) {
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.cancelled,
          RunPhase.completed,
          Optional.of("Unable to find WorkflowRun"));

      lockManager.releaseLock(taskExecutionId, taskTokenId);
      LOGGER.info("[{}] Released TaskRun ({}) lock", taskExecutionId, taskExecutionId);
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

      lockManager.releaseLock(taskExecutionId, taskTokenId);
      LOGGER.info("[{}] Released TaskRun ({}) lock", taskExecutionId, taskExecutionId);
      return;
    } else {
      // Update TaskRun with current TaskExecution status
      // updateStatusAndSaveTask is not used as we leave the Status to what user provided
      LOGGER.info("[{}] Marking Task as {}.", taskExecutionId, taskExecution.getStatus());
      long duration =
          taskExecution.getStartTime() != null
              ? new Date().getTime() - taskExecution.getStartTime().getTime()
              : 0;
      taskExecution.setDuration(duration);
      taskExecution.setPhase(RunPhase.completed);
      taskExecution = taskRunRepository.save(taskExecution);

      lockManager.releaseLock(taskExecutionId, taskTokenId);
      LOGGER.info("[{}] Released TaskRun ({}) lock", taskExecutionId, taskExecutionId);
    }

    // Execute Next Task (checking timeouts)
    // Check happens after saving the TaskRun to ensure we correctly record the provided user
    // details but no further execution can happen
    if (hasWorkflowRunExceededTimeout(wfRunEntity.get())) {
      // This will update the Workflow status and then call back to this method to be trapped by
      // above WorkflowRun checks
      workflowRunService.timeout(wfRunEntity.get().getId(), false);
      return;
    } else if (RunStatus.timedout.equals(taskExecution.getStatus())
        || hasTaskRunExceededTimeout(taskExecution)) {
      long duration =
          taskExecution.getStartTime() != null
              ? new Date().getTime() - taskExecution.getStartTime().getTime()
              : 0;
      taskExecution.setDuration(duration);
      updateStatusAndSaveTask(
          taskExecution,
          RunStatus.timedout,
          RunPhase.completed,
          Optional.of("The TaskRun exceeded the timeout. Timeout was set to {} minutes"),
          taskExecution.getTimeout());
      workflowRunService.timeout(wfRunEntity.get().getId(), true);
      return;
    }

    LOGGER.info(
        "[{}] Attempting to get WorkflowRun ({}) lock", taskExecutionId, wfRunEntity.get().getId());
    String tokenId = lockManager.acquireLock(wfRunEntity.get().getId());
    LOGGER.info("[{}] Obtained WorkflowRun ({}) lock", taskExecutionId, wfRunEntity.get().getId());

    List<TaskRunEntity> tasks = dagUtility.retrieveTaskList(wfRunEntity.get().getId());
    boolean finishedAllDependencies = this.finishedAll(wfRunEntity.get(), tasks, taskExecution);
    LOGGER.debug("[{}] Finished all TaskRuns? {}", taskExecutionId, finishedAllDependencies);

    // Refresh wfRunEntity and update approval status
    wfRunEntity = workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    updatePendingAprovalStatus(wfRunEntity.get());

    executeNextStep(wfRunEntity.get(), tasks, taskExecution, finishedAllDependencies);

    lockManager.releaseLock(wfRunEntity.get().getId(), tokenId);
    LOGGER.info("[{}] Released WorkflowRun ({}) lock", taskExecutionId, wfRunEntity.get().getId());
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
          taskExecution.setStatus(RunStatus.timedout);
          taskExecutionClient.end(this, taskExecution);
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

  private void updatePendingAprovalStatus(WorkflowRunEntity wfRunEntity) {
    long count =
        actionRepository.countByWorkflowRunRefAndStatus(
            wfRunEntity.getId(), ActionStatus.submitted);
    boolean existingApprovals = (count > 0);
    wfRunEntity.setAwaitingApproval(existingApprovals);
    this.workflowRunRepository.save(wfRunEntity);
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

      boolean executeTask = canExecuteTask(wfRunEntity, next);
      if (executeTask) {
        LOGGER.debug("[{}] Execute next TaskRun: {}", wfRunEntity.getId(), next.getName());
        Optional<TaskRunEntity> taskRunEntity =
            this.taskRunRepository.findById(currentTask.getId());
        if (!taskRunEntity.isPresent()) {
          LOGGER.error("Reached node which should not be executed.");
        } else {
          taskExecutionClient.queue(this, next);
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
              this.taskRunRepository.findFirstByNameAndWorkflowRunRef(
                  dep.getTaskRef(), wfRunEntity.getId());
          if (!taskRunEntity.isPresent() && taskRunEntity != null) {
            continue;
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

  private boolean canExecuteTask(WorkflowRunEntity wfRunEntity, TaskRunEntity next) {
    List<WorkflowTaskDependency> deps = next.getDependencies();
    LOGGER.debug("Found {} dependencies", deps.size());
    for (WorkflowTaskDependency dep : deps) {
      Optional<TaskRunEntity> taskRunEntity =
          this.taskRunRepository.findFirstByNameAndWorkflowRunRef(
              dep.getTaskRef(), wfRunEntity.getId());
      if (taskRunEntity.isPresent()) {
        RunPhase phase = taskRunEntity.get().getPhase();
        if (!RunPhase.completed.equals(phase)) {
          return false;
        }
      }
    }
    return true;
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
