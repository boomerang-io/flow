package io.boomerang.engine;

import tools.jackson.databind.ObjectMapper;
import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.*;
import io.boomerang.common.model.*;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.engine.entity.TaskLockEntity;
import io.boomerang.engine.model.ChildWorkflowRunCreated;
import io.boomerang.engine.model.ScheduleRequested;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.workflow.WorkflowService;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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

  // Backoff between acquirelock re-attempts while a lock is held by another task.
  private static final long LOCK_RETRY_BACKOFF_MILLIS = 5000;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Autowired private DAGUtility dagUtility;

  @Autowired private WorkflowRunRepository workflowRunRepository;

  @Autowired private WorkflowRunService workflowRunService;

  @Autowired private WorkflowService workflowService;

  @Autowired private TaskRunRepository taskRunRepository;

  @Autowired @Lazy private TaskRunService taskRunService;

  @Autowired private ActionRepository actionRepository;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private ApplicationEventPublisher eventPublisher;

  @Autowired private ParameterManager paramManager;

  // Proxy to self so internal hand-offs go through the @Async proxy and hop threads.
  @Autowired @Lazy private TaskExecutionService self;


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

    // The single pause gate: a paused run admits nothing. Level-triggered - resume reconciles
    // and resumes this task through queue.
    if (wfRunEntity.get().getPauseRequestedAt() != null) {
      LOGGER.info("[{}] WorkflowRun is paused. TaskRun awaits resume.", taskExecutionId);
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
      if (taskRunService.tryAdmit(taskExecutionId, taskExecution.getParams()) == null) {
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

    // Execution-entry Compare-And-Set: ready becomes running exactly once, baking the durable
    // timeoutAt deadline. A duplicate dispatch of the same TaskRun loses here and performs no
    // side effects.
    taskExecution =
        taskRunService.tryStartExecution(taskExecutionId, new Date(), taskExecution.getTimeout());
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
        // Ends only when the lock is acquired; otherwise the task parks as waiting for resume.
        endTask = this.acquireTaskLock(taskExecution, wfRunEntity);
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
        // Parks as waiting until its duration elapses; the watcher completes it, no held thread.
        this.createSleepTask(taskExecution);
      }
      case end, start -> throw new UnsupportedOperationException("Unimplemented case: " + taskType);
      default -> throw new BoomerangException(BoomerangError.TASKRUN_INVALID_TYPE, taskType);
    }

    // Timeouts are owned by the watcher sweep on the durable timeoutAt deadline - there are no
    // in-memory timers to lose on a crash.
    if (endTask) {
      // Persist the type-specific outcome (status, results, decision value) for end() to re-read.
      taskRunRepository.save(taskExecution);
      self.end(taskExecutionId);
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
      taskRunService.tryComplete(
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
      taskRunService.tryComplete(
          taskExecutionId,
          Optional.of(RunStatus.cancelled),
          Optional.of(statusMessage),
          duration,
          claimedBy,
          claimSeq);
      return;
    }

    // The TaskRun timed out when the caller (or the watcher's reap) marked it so; wall-clock
    // checks are gone - the durable timeoutAt deadline is the single timeout path.
    boolean taskTimedOut = RunStatus.timedout.equals(taskExecution.getStatus());

    // Completion Compare-And-Set: the caller-persisted terminal status stands unless the task
    // timed out. Losing means another end already completed this TaskRun.
    TaskRunEntity preImage =
        taskRunService.tryComplete(
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

    // Winner-only follow-on: a final task timeout times out the run rather than advancing it.
    if (taskTimedOut) {
      workflowRunService.timeout(wfRunEntity.getId(), true);
      return;
    }

    // Winner-only graph advance. No workflow-level lock: admission and completion
    // Compare-And-Set transitions make a duplicate or concurrent advance a no-op.
    List<TaskRunEntity> tasks = dagUtility.retrieveTaskList(wfRunEntity.getId());
    boolean finishedAllDependencies = this.finishedAll(wfRunEntity, tasks, taskExecution);
    LOGGER.debug("[{}] Finished all TaskRuns? {}", taskExecutionId, finishedAllDependencies);

    // Approval state only changes when an approval or manual task resolves; skip the refetch
    // and recompute for every other task completion.
    if (TaskType.approval.equals(taskExecution.getType())
        || TaskType.manual.equals(taskExecution.getType())) {
      wfRunEntity =
          workflowRunRepository.findById(taskExecution.getWorkflowRunRef()).orElse(wfRunEntity);
      updatePendingApprovalStatus(wfRunEntity);
    }

    executeNextStep(wfRunEntity, tasks, taskExecution, finishedAllDependencies);
  }

  /*
   * Recover the graph advance for a run whose advancing winner was lost (for example a crash
   * between completing a task and queueing its dependants). Safe to call at any time: admission
   * and completion Compare-And-Set transitions make a duplicate advance a no-op.
   */
  public void advance(String wfRunId) {
    WorkflowRunEntity wfRunEntity = workflowRunRepository.findById(wfRunId).orElse(null);
    if (wfRunEntity == null || !RunPhase.running.equals(wfRunEntity.getPhase())) {
      return;
    }
    List<TaskRunEntity> tasks = dagUtility.retrieveTaskList(wfRunId);
    for (TaskRunEntity completed :
        tasks.stream()
            .filter(
                t ->
                    RunPhase.completed.equals(t.getPhase()) && !TaskType.end.equals(t.getType()))
            .toList()) {
      boolean finishedAllDependencies = this.finishedAll(wfRunEntity, tasks, completed);
      executeNextStep(wfRunEntity, tasks, completed, finishedAllDependencies);
    }
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
    List<TaskWorkspace> taskWorkspaces = new LinkedList<>();
    wfRunEntity
        .getWorkspaces()
        .forEach(
            ws -> {
              TaskWorkspace tw = new TaskWorkspace();
              WorkflowWorkspaceSpec spec =
                  OBJECT_MAPPER.convertValue(ws.getSpec(), WorkflowWorkspaceSpec.class);
              tw.setName(ws.getName());
              tw.setMountPath(spec.getMountPath());
              tw.setOptional(ws.isOptional());
              tw.setType(ws.getType());
              taskWorkspaces.add(tw);
            });
    taskExecution.setWorkspaces(taskWorkspaces);
  }

  private void updatePendingApprovalStatus(WorkflowRunEntity wfRunEntity) {
    boolean existingApprovals =
        actionRepository.existsByWorkflowRunRefAndStatus(
            wfRunEntity.getId(), ActionStatus.submitted);
    wfRunEntity.setAwaitingApproval(existingApprovals);
    // Field-scoped write so a level-triggered recompute can never stomp concurrent run state.
    this.workflowRunService.setAwaitingApproval(wfRunEntity.getId(), existingApprovals);
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
    // Durable wait: park as waiting and let the watcher complete it when waitUntil elapses. No
    // held thread survives a crash - the sweep resumes it.
    taskExecution.setStatus(RunStatus.waiting);
    taskExecution.setWaitUntil(new Date(System.currentTimeMillis() + duration));
    taskRunRepository.save(taskExecution);
  }

  private void processDecision(TaskRunEntity taskExecution, String activityId) {
    String decisionValue = ParameterUtil.getValue(taskExecution.getParams(), "value").toString();
    String value = decisionValue;
    taskExecution.setDecisionValue(value);
    taskExecution.setStatus(RunStatus.succeeded);
  }

  /*
   * Returns true when the task should end now (lock acquired, or invalid params - fail); false when
   * it parked as waiting for the watcher to resume. Acquiring is an atomic insert-or-expired
   * takeover, so two tasks can never both hold the key. The task's timeoutAt bounds the wait - the
   * timeout reap fails it if the lock never frees.
   */
  private boolean acquireTaskLock(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    List<RunParam> params = taskExecution.getParams();
    Long timeout = null;
    if (ParameterUtil.containsName(params, "timeout")) {
      String timeoutStr = ParameterUtil.getValue(params, "timeout").toString();
      if (!timeoutStr.isBlank() && NumberUtils.isCreatable(timeoutStr)) {
        timeout = Long.valueOf(timeoutStr);
      }
    }
    String scopedKey = scopedLockKey(taskExecution);
    if (Objects.isNull(scopedKey) || Objects.isNull(timeout)) {
      taskExecution.setStatus(RunStatus.failed);
      taskExecution.setStatusMessage("acquirelock requires a key and a timeout.");
      return true;
    }

    Date expiresAt = new Date(System.currentTimeMillis() + timeout);
    TaskLockEntity lock =
        tryAcquire(scopedKey, taskExecution.getId(), wfRunEntity.getId(), expiresAt);
    if (lock != null) {
      LOGGER.debug("[{}] Acquired lock for key: {}", taskExecution.getId(), scopedKey);
      taskExecution.setStatus(RunStatus.succeeded);
      return true;
    }
    // Held by another task: park as waiting and re-attempt after the backoff.
    LOGGER.debug("[{}] Lock held for key: {}. Waiting to retry.", taskExecution.getId(), scopedKey);
    taskExecution.setStatus(RunStatus.waiting);
    taskExecution.setWaitUntil(new Date(System.currentTimeMillis() + LOCK_RETRY_BACKOFF_MILLIS));
    taskRunRepository.save(taskExecution);
    return false;
  }

  private void releaseTaskLock(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    String scopedKey = scopedLockKey(taskExecution);
    if (Objects.isNull(scopedKey)) {
      taskExecution.setStatus(RunStatus.failed);
      taskExecution.setStatusMessage("releaselock requires a key.");
      return;
    }
    // Holder-checked delete; a missing or expired lock is already released - idempotent.
    LOGGER.debug("[{}] Releasing lock for key: {}", taskExecution.getId(), scopedKey);
    release(scopedKey, taskExecution.getId());
    taskExecution.setStatus(RunStatus.succeeded);
  }

  // Acquire the cross-workflow lock in one atomic step: take it when the key is unheld or its
  // lease has expired, never stealing a live lock held by another task. Returns the acquired
  // document when this task now holds it, or null when another task holds a live lease.
  // Package-private so the lock-semantics test can exercise it directly.
  TaskLockEntity tryAcquire(
      String scopedKey, String taskRunRef, String workflowRunRef, Date expiresAt) {
    Date now = new Date();
    // Upsert only matches an unheld or expired key, so a live lock held by another task is never
    // stolen. When the key is held live the upsert tries to insert a duplicate _id and the unique
    // key throws - that is the "not acquired" signal.
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(scopedKey)
                .orOperator(
                    Criteria.where("expiresAt").exists(false),
                    Criteria.where("expiresAt").lte(now)));
    Update update =
        new Update()
            .set("holder", taskRunRef)
            .set("workflowRunRef", workflowRunRef)
            .set("acquiredAt", now)
            .set("expiresAt", expiresAt);
    try {
      TaskLockEntity lock =
          mongoTemplate.findAndModify(
              query,
              update,
              FindAndModifyOptions.options().upsert(true).returnNew(true),
              TaskLockEntity.class);
      return (lock != null && taskRunRef.equals(lock.getHolder())) ? lock : null;
    } catch (DuplicateKeyException held) {
      return null;
    }
  }

  // Release only when held by this task; idempotent - a missing or expired lock is a no-op.
  // Package-private so the lock-semantics test can exercise it directly.
  void release(String scopedKey, String taskRunRef) {
    mongoTemplate.remove(
        Query.query(Criteria.where("_id").is(scopedKey).and("holder").is(taskRunRef)),
        TaskLockEntity.class);
  }

  // The lock key scoped by workspace so the same user key never collides across workspaces. The
  // scope is the team-name annotation (a param-context annotation retired in a later cleanup).
  private String scopedLockKey(TaskRunEntity taskExecution) {
    List<RunParam> params = taskExecution.getParams();
    if (!ParameterUtil.containsName(params, "key")) {
      return null;
    }
    String key = ParameterUtil.getValue(params, "key").toString();
    if (taskExecution.getAnnotations() != null
        && taskExecution.getAnnotations().containsKey("boomerang.io/team-name")) {
      return taskExecution.getAnnotations().get("boomerang.io/team-name").toString() + ":" + key;
    }
    return key;
  }

  /*
   * Resume a due waiting task the watcher claimed: a slept task completes, an acquirelock task
   * re-attempts the acquire (succeeding, or re-parking for another backoff).
   */
  @Async("asyncTaskExecutor")
  public void resumeWaitingTask(String taskRunId) {
    TaskRunEntity taskExecution = taskRunRepository.findById(taskRunId).orElse(null);
    if (taskExecution == null) {
      return;
    }
    if (TaskType.sleep.equals(taskExecution.getType())) {
      taskExecution.setStatus(RunStatus.succeeded);
      taskRunRepository.save(taskExecution);
      self.end(taskRunId);
      return;
    }
    if (TaskType.acquirelock.equals(taskExecution.getType())) {
      WorkflowRunEntity wfRunEntity =
          workflowRunRepository.findById(taskExecution.getWorkflowRunRef()).orElse(null);
      if (wfRunEntity == null) {
        return;
      }
      if (acquireTaskLock(taskExecution, wfRunEntity)) {
        taskRunRepository.save(taskExecution);
        self.end(taskRunId);
      }
    }
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
        eventPublisher.publishEvent(
            new ChildWorkflowRunCreated(workflowRef, wfRunResponse.getId()));
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
    // No save here - the execute() endTask branch persists this same taskExecution.
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
          eventPublisher.publishEvent(new ScheduleRequested(schedule));
          LOGGER.debug(
              "Workflow Schedule requested for workflow (" + schedule.getWorkflowRef() + ").");
          taskExecution.setStatus(RunStatus.succeeded);
          return;
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
    // Atomic single-field set - no lock, no full-document rewrite.
    workflowRunService.setAwaitingApproval(wfRunEntity.getId(), true);
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

    RunResult wfResult = new RunResult();
    wfResult.setName(output);
    wfResult.setValue(input);
    // Atomic append - no lock, no read-modify-write, so concurrent writers cannot lose a result.
    workflowRunService.appendResult(wfRunEntity.getId(), wfResult);
    taskExecution.setStatus(RunStatus.succeeded);
  }

  private void finishWorkflow(WorkflowRunEntity wfRunEntity, List<TaskRunEntity> tasks) {
    // Terminalise the end node via the completion Compare-And-Set (a no-op on recovery).
    tasks.stream()
        .filter(t -> TaskType.end.equals(t.getType()))
        .forEach(
            t ->
                taskRunService.tryComplete(
                    t.getId(),
                    Optional.of(RunStatus.succeeded),
                    Optional.empty(),
                    0,
                    Optional.empty(),
                    Optional.empty()));
    // Validate all paths have been taken
    // It also updates the status of each task and checks dependencies.
    boolean workflowCompleted = dagUtility.validateWorkflow(wfRunEntity, tasks);

    RunStatus status =
        wfRunEntity.getStatusOverride() != null
            ? wfRunEntity.getStatusOverride()
            : (workflowCompleted ? RunStatus.succeeded : RunStatus.failed);
    long duration =
        wfRunEntity.getStartTime() != null
            ? new Date().getTime() - wfRunEntity.getStartTime().getTime()
            : 0;

    // Completion Compare-And-Set: running becomes completed exactly once, so racing advances
    // (or a concurrent cancel/timeout) can never complete the run twice or stomp its status.
    if (workflowRunService.tryComplete(
            wfRunEntity.getId(), List.of(RunPhase.running), status, null, duration)
        == null) {
      LOGGER.info(
          "[{}] WorkflowRun already completed. Only the completion winner finishes.",
          wfRunEntity.getId());
      return;
    }
    LOGGER.info("[{}] Completed Workflow with status: {}.", wfRunEntity.getId(), status);
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
        self.queue(next.getId());
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
    if (message.isPresent()) {
      String formatted = MessageFormatter.arrayFormat(message.get(), messageArgs).getMessage();
      taskExecution.setStatusMessage(formatted);
      if (RunStatus.failed.equals(status)) {
        LOGGER.error(formatted);
      }
    }
    taskExecution.setStatus(status);
    taskExecution.setPhase(phase);
    taskRunRepository.save(taskExecution);
  }
}
