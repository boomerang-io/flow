package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import tools.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.*;
import io.boomerang.common.model.*;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.core.SettingsService;
import io.boomerang.engine.entity.TaskLockEntity;
import io.boomerang.common.model.ChildWorkflowRunCreated;
import io.boomerang.common.model.ScheduleRequested;
import io.boomerang.common.model.StatusReason;
import io.boomerang.engine.model.TaskOutcome;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.workflow.ParamLayerService;
import io.boomerang.workflow.WorkflowService;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.bson.BsonMaximumSizeExceededException;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

  // runscheduledworkflow's "time" param (A20): tolerant of both "H:mm" and "HH:mm" - single
  // digit hours parsed the same way String.split(":") + Integer.valueOf(...) already did.
  private static final DateTimeFormatter RUN_SCHEDULED_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("H:mm");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // The child-workflow nesting cap, and the typed cause written when a task trips it. The cap
  // keeps a workflow that runs itself from recursing until it exhausts the workspace quota.
  public static final String WORKFLOWRUN_SETTINGS_KEY = "workflowrun";
  static final String MAX_NESTING_DEPTH = "max.nesting.depth";
  static final int DEFAULT_MAX_NESTING_DEPTH = 5;

  // The foreach item cap, read from the same settings document, and the typed causes a foreach
  // task fails with. The cap is public because saving a workflow checks a literal item array
  // against it too.
  public static final String MAX_FOREACH_ITEMS = "max.foreach.items";
  public static final int DEFAULT_MAX_FOREACH_ITEMS = 256;
  // The typed cause a task fails with at admission when a Flow reference does not resolve.
  // MongoDB's codes for a document that grows past its 16 MB limit: BSONObjectTooLarge, and an
  // update whose resulting document is too large.
  private static final Set<Integer> DOCUMENT_TOO_LARGE_CODES = Set.of(10334, 17419);

  @Value("${flow.engine.task.params.max-bytes:16384}")
  private int paramsMaxBytes;

  @Autowired private DAGUtility dagUtility;

  @Autowired private WorkflowRunRepository workflowRunRepository;

  @Autowired private WorkflowRunService workflowRunService;

  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;

  @Autowired private WorkflowService workflowService;

  @Autowired private TaskRunRepository taskRunRepository;

  @Autowired @Lazy private TaskRunService taskRunService;

  @Autowired private ActionRepository actionRepository;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private ApplicationEventPublisher eventPublisher;

  @Autowired private ParameterManager paramManager;

  @Autowired private SettingsService settingsService;

  @Autowired private ParamLayerService paramLayerService;

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
    LOGGER.info("[{}] Received queue task request: {}", taskExecutionId, taskExecution.getName());

    // Check if TaskRun Phase is valid
    if (!RunPhase.pending.equals(taskExecution.getPhase())) {
      LOGGER.error("[{}] Task Status invalid. Cannot queue task.", taskExecutionId);
      return;
    }

    // Check if WorkflowRun Phase is valid
    Optional<WorkflowRunEntity> wfRunEntity =
        workflowRunRepository.findById(taskExecution.getWorkflowRunRef());
    if (!wfRunEntity.isPresent()) {
      taskRunService.tryComplete(taskExecution, RunStatus.cancelled, "Unable to find WorkflowRun");
      return;
    } else if (RunPhase.completed.equals(wfRunEntity.get().getPhase())) {
      taskRunService.tryComplete(
          taskExecution,
          RunStatus.skipped,
          "[{}] WorkflowRun has been marked as {}. TaskRun was never queued, setting TaskRun as Skipped.",
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

    // Ensure Task is valid as part of Graph. A foreach item is not a vertex - its parent passed
    // this check when it fanned out.
    boolean canRunTask =
        taskExecution.getParentRef() != null
            || dagUtility.canRunTask(
                dagUtility.retrieveGraph(wfRunEntity.get().getId()), taskExecution);
    LOGGER.debug("[{}] Can run task? {}", taskExecutionId, canRunTask);

    if (canRunTask) {
      // A foreach task fans out into items instead of being admitted. Each item resolves its own
      // params when it is queued, so $(params.item) and $(params.index) resolve per item.
      if (taskExecution.getForeach() != null) {
        fanOut(wfRunEntity.get(), taskExecution);
        return;
      }

      // Resolve Parameter Substitutions. A Flow reference in the params or the spec that does not
      // resolve fails the task here, before it is claimable: end() completes it from notstarted in
      // one write, and a caller that lost the race to admission changes nothing.
      Map<String, Set<String>> unresolved =
          paramManager.resolveParamLayers(wfRunEntity.get(), Optional.of(taskExecution));
      if (!unresolved.isEmpty()) {
        String message = ParameterManager.unresolvedMessage(taskExecution.getName(), unresolved);
        LOGGER.error("[{}] {}", taskExecutionId, message);
        self.end(
            taskExecutionId,
            TaskOutcome.refused(RunStatus.failed, StatusReason.PARAM_UNRESOLVED, message));
        return;
      }

      // Engine-enforced params cap, identical on every executor and checked BEFORE admission so
      // an oversize task is never claimable. Substrate limits differ (128 KiB per env string,
      // 1.5 MiB pod object, 4 KB Lambda env) - the cap makes the failure mode a clear engine
      // message instead of a container that crashes at exec.
      byte[] paramBytes = OBJECT_MAPPER.writeValueAsBytes(taskExecution.getParams());
      if (paramBytes.length > paramsMaxBytes) {
        String message =
            "PARAMS_TOO_LARGE - resolved params total "
                + paramBytes.length
                + " bytes, exceeding the "
                + paramsMaxBytes
                + " byte cap. Pass large values by reference (workspace path or URI).";
        LOGGER.error("[{}] {}", taskExecutionId, message);
        self.end(taskExecutionId, TaskOutcome.refused(RunStatus.invalid, null, message));
        return;
      }

      // An ai task run is labelled with the model its resolved model param asks for, unless that
      // param is a secret.
      if (TaskType.ai.equals(taskExecution.getType())) {
        taskExecution.getParams().stream()
            .filter(p -> "model".equalsIgnoreCase(p.getName()))
            .filter(p -> !ParamType.secret.equals(p.getType()))
            .map(RunParam::getValue)
            .filter(value -> value instanceof String text && !text.isEmpty())
            .findFirst()
            .ifPresent(value -> taskExecution.getLabels().put(ServerLabel.MODEL, (String) value));
      }

      // Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved
      // params and the labels in the same guarded write. A duplicate queue of the same TaskRun
      // (e.g. a join queued by both parents) loses here and performs no side effects. The spec is
      // not passed: it is stored with its references and filled in when a dispatcher claims it.
      if (taskRunService.tryAdmit(
              taskExecutionId, taskExecution.getParams(), taskExecution.getLabels())
          == null) {
        LOGGER.info("[{}] TaskRun already admitted. Nothing to do.", taskExecutionId);
        return;
      }

      // Auto start System related tasks skipping the start checks
      if (!TaskType.template.equals(taskExecution.getType())
          && !TaskType.script.equals(taskExecution.getType())
          && !TaskType.custom.equals(taskExecution.getType())
          && !TaskType.generic.equals(taskExecution.getType())
          && !TaskType.ai.equals(taskExecution.getType())
          && !TaskType.uploadartifact.equals(taskExecution.getType())
          && !TaskType.downloadartifact.equals(taskExecution.getType())) {
        LOGGER.debug("[{}] Moving task to Executing: {}", taskExecutionId, taskExecution.getName());
        self.execute(taskExecutionId);
      }
    } else {
      LOGGER.debug("[{}] Skipping task: {}", taskExecutionId, taskExecution.getName());
      // Completed as skipped in one write, from notstarted only: a join queued by both parents,
      // where the other parent already admitted, started or had it claimed, is left alone.
      self.end(taskExecutionId, TaskOutcome.skipped());
    }
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
    LOGGER.info("[{}] Received start task request.", taskExecutionId);

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
      taskRunService.tryComplete(taskExecution, RunStatus.cancelled, "Unable to find WorkflowRun");
      return;
    } else if (RunPhase.completed.equals(wfRunEntity.get().getPhase())) {
      taskRunService.tryComplete(
          taskExecution,
          RunStatus.cancelled,
          "[{}] WorkflowRun has been marked as {}. Setting TaskRun as Cancelled. TaskRun may still run to completion.",
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
    LOGGER.info("[{}] Received Execute task request for type: {}.", taskExecutionId, taskType);

    // Execution-entry Compare-And-Set: ready becomes running exactly once, baking the durable
    // timeoutAt deadline. A duplicate dispatch of the same TaskRun loses here and performs no
    // side effects. A wait is bounded by its own declared timeout alone, never by the run's
    // budget or the operator's per-task ceiling - a gate nobody answers is indefinite.
    Long budget =
        EngineConstants.WAITING_TYPES.contains(taskType)
            ? declaredTimeout(taskExecution, wfRunEntity)
            : taskExecution.getTimeout();
    taskExecution = taskRunService.tryStartExecution(taskExecutionId, new Date(), budget);
    if (taskExecution == null) {
      LOGGER.info("[{}] TaskRun already executing or completed. Nothing to do.", taskExecutionId);
      return;
    }
    // A task executing means the run is no longer idle at a wait: its clock runs again.
    workflowRunStateHelper.tryResumeWaiting(wfRunId);

    // If new TaskTypes are added, the following code needs updated as well as the IF statement at
    // the end of QUEUE
    // TaskRunEntities are typically only updated and then passed to end
    // If not ending, then they may save a waiting status.
    switch (taskType) {
      case template, script, custom, generic, ai, uploadartifact, downloadartifact -> {
        // Nothing to do here. These types wait for a Handler. The workspaces this task mounts
        // were materialised from its own declaration when the TaskRun was created.
      }
      case decision -> {
        processDecision(taskExecution);
        taskExecution.setStatus(RunStatus.succeeded);
        endTask = true;
      }
      case acquirelock -> {
        // Ends only when the lock is acquired; otherwise the task parks as waiting for resume.
        endTask = this.acquireTaskLock(taskExecution, wfRunEntity);
      }
      case releaselock -> {
        this.releaseTaskLock(taskExecution);
        endTask = true;
      }
      case runworkflow -> {
        // Ends now for a fire-and-forget child; with wait=true the task parks as waiting and the
        // child's own completion ends it.
        endTask = this.runWorkflow(taskExecution, wfRunEntity);
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
      // The type-specific outcome (status, message, results, decision value) is written by end()'s
      // completion itself, so a timeout that landed meanwhile is never rolled back.
      self.end(taskExecutionId, TaskOutcome.executed(taskExecution));
    } else if (EngineConstants.WAITING_TYPES.contains(taskType)) {
      markWaitingIfIdle(wfRunId);
    }
  }

  /*
   * The timeout the revision's task declares for itself, in minutes, or null when it declares
   * none. A foreach item reads its parent's declaration.
   */
  private Long declaredTimeout(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    WorkflowRevisionEntity revision =
        paramLayerService.getRevision(wfRunEntity.getWorkflowRevisionRef());
    if (revision == null || revision.getTasks() == null) {
      return null;
    }
    String name =
        (taskExecution.getParentRef() != null)
            ? taskExecution.getName().replaceAll("\\[\\d+]$", "")
            : taskExecution.getName();
    return revision.getTasks().stream()
        .filter(task -> name.equals(task.getName()))
        .findFirst()
        .map(WorkflowTask::getTimeout)
        .orElse(null);
  }

  /*
   * Stop the run clock when nothing is executing: every in-flight task is waiting on a person,
   * an event, a child run, the clock or a lock. Any task that starts executing restarts it.
   */
  private void markWaitingIfIdle(String wfRunId) {
    if (taskRunService.allInFlightWaiting(wfRunId)) {
      workflowRunStateHelper.tryMarkWaiting(wfRunId);
    }
  }

  /*
   * End a task whose status a caller already recorded, such as a timeout or a cancel.
   */
  @Async("asyncTaskExecutor")
  public void end(String taskRunId) {
    end(taskRunId, null, Optional.empty(), Optional.empty());
  }

  /*
   * End a task with the outcome the engine decided for it, written by the completion itself.
   */
  @Async("asyncTaskExecutor")
  public void end(String taskRunId, TaskOutcome outcome) {
    end(taskRunId, outcome, Optional.empty(), Optional.empty());
  }

  /*
   * End a task with an outcome whose results may not fit MongoDB's 16 MB document limit - a
   * for-each parent's combined results - ending it with the fallback outcome when they do not.
   */
  @Async("asyncTaskExecutor")
  public void end(String taskRunId, TaskOutcome outcome, TaskOutcome ifTooLarge) {
    try {
      end(taskRunId, outcome, Optional.empty(), Optional.empty());
    } catch (RuntimeException ex) {
      if (!isDocumentTooLarge(ex)) {
        throw ex;
      }
      LOGGER.error("[{}] {}", taskRunId, ifTooLarge.statusMessage());
      end(taskRunId, ifTooLarge, Optional.empty(), Optional.empty());
    }
  }

  /*
   * End with claimant identity, for the status a dispatcher or an action already recorded.
   */
  public void end(String taskRunId, Optional<String> claimedBy, Optional<Long> claimSeq) {
    end(taskRunId, null, claimedBy, claimSeq);
  }

  /*
   * The completion Compare-And-Set admits exactly one winner - a terminal status can never be
   * overwritten - and only the winner advances the graph. A loser (duplicate end, an end racing a
   * timeout, or an outcome for a task that has moved on) logs and returns. A timeout already
   * recorded wins over an outcome.
   */
  private void end(
      String taskRunId,
      TaskOutcome outcome,
      Optional<String> claimedBy,
      Optional<Long> claimSeq) {
    // Re-read at entry so the transition acts on fresh state, not a caller's snapshot.
    TaskRunEntity taskExecution = taskRunRepository.findById(taskRunId).orElse(null);
    if (taskExecution == null) {
      LOGGER.error("[{}] Unable to find TaskRun to end.", taskRunId);
      return;
    }
    String taskExecutionId = taskExecution.getId();
    LOGGER.info("[{}] Received end task request.", taskExecutionId);

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
    if (RunPhase.completed.equals(wfRunEntity.getPhase())) {
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

    // Completion Compare-And-Set: the outcome, or else the caller-recorded terminal status, stands
    // unless the task timed out. Losing means another end already completed this TaskRun, or the
    // outcome's task has moved on.
    TaskRunEntity preImage =
        (outcome != null && !taskTimedOut)
            ? taskRunService.tryComplete(taskExecutionId, outcome, duration)
            : taskRunService.tryComplete(
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
          "[{}] TaskRun already completed or moved on. Only the completion winner advances.",
          taskExecutionId);
      return;
    }
    LOGGER.info(
        "[{}] Marking Task as {}.",
        taskExecutionId,
        (taskTimedOut
            ? RunStatus.timedout
            : (outcome != null ? outcome.status() : taskExecution.getStatus())));
    // The task that ended may have been the wait the run was idle at: the clock runs again.
    workflowRunStateHelper.tryResumeWaiting(wfRunEntity.getId());

    // Winner-only follow-on: a final task timeout times out the run rather than advancing it.
    if (taskTimedOut) {
      workflowRunService.timeout(wfRunEntity.getId(), true);
      return;
    }

    // An item never advances the graph: it completes its parent once every item is terminal, and
    // the parent's own end advances.
    if (taskExecution.getParentRef() != null) {
      completeParentIfItemsTerminal(taskExecution.getParentRef());
      return;
    }

    // Winner-only graph advance. No workflow-level lock: admission and completion
    // Compare-And-Set transitions make a duplicate or concurrent advance a no-op.
    List<TaskRunEntity> tasks = dagUtility.retrieveGraph(wfRunEntity.getId());
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
    // With this task gone, the run may be left with only waits in flight (a branch still at a
    // gate while the other finished): its clock stops until something executes again.
    markWaitingIfIdle(wfRunEntity.getId());
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
    List<TaskRunEntity> tasks = dagUtility.retrieveGraph(wfRunId);
    for (TaskRunEntity completed :
        tasks.stream()
            .filter(
                t ->
                    RunPhase.completed.equals(t.getPhase()) && !TaskType.end.equals(t.getType()))
            .toList()) {
      boolean finishedAllDependencies = this.finishedAll(wfRunEntity, tasks, completed);
      executeNextStep(wfRunEntity, tasks, completed, finishedAllDependencies);
    }
    // A running foreach task re-queues the items a pause held back, and recovers a fan-out or a
    // parent completion that a crash cut short.
    tasks.stream()
        .filter(t -> t.getForeach() != null && RunPhase.running.equals(t.getPhase()))
        .forEach(t -> fanOutItems(t.getId()));
  }

  /*
   * Fan out a foreach task: resolve its items to a JSON array within the cap, then move it to
   * running through its own Compare-And-Set - never through admission, so the parent is never
   * claimable - and create and queue one item per element. Items that do not resolve to an array,
   * or exceed the cap, fail the task with a typed reason. An empty array succeeds it at once.
   */
  private void fanOut(WorkflowRunEntity wfRunEntity, TaskRunEntity parent) {
    List<Object> items =
        foreachItemsOf(
            paramManager.resolveParamValue(wfRunEntity, parent, parent.getForeach().getItems()));
    int cap = maxForeachItems();
    String reason = null;
    String message = null;
    if (items == null) {
      reason = StatusReason.FOREACH_ITEMS_INVALID;
      message = "The for-each items did not resolve to a JSON array.";
    } else if (items.size() > cap) {
      reason = StatusReason.FOREACH_TOO_MANY_ITEMS;
      message =
          "The for-each items resolved to " + items.size() + " items, over the cap of " + cap + ".";
    }
    if (taskRunService.tryStartForeach(parent.getId(), new Date(), (reason == null ? items : null))
        == null) {
      LOGGER.info("[{}] Foreach TaskRun already fanned out. Nothing to do.", parent.getId());
      return;
    }
    if (reason != null) {
      LOGGER.error("[{}] {}", parent.getId(), message);
      self.end(parent.getId(), TaskOutcome.ofItems(RunStatus.failed, reason, message, null));
      return;
    }
    fanOutItems(parent.getId());
  }

  /*
   * Create any missing item of a running foreach parent, queue every item not yet admitted, then
   * complete the parent if its items are all terminal. Idempotent, so the fan-out, a resume and the
   * watcher's recovery all call it: an item is created only when its name is free - the
   * (workflowRunRef, name) unique index makes a concurrent duplicate lose - and queue admits an
   * item at most once.
   */
  public void fanOutItems(String parentId) {
    TaskRunEntity parent = taskRunRepository.findById(parentId).orElse(null);
    if (parent == null || !RunPhase.running.equals(parent.getPhase()) || parent.getForeach() == null) {
      return;
    }
    if (!(parent.getForeach().getItems() instanceof List<?> items)) {
      // Refused at fan-out - no items stored - and its end was lost: end it the same way.
      self.end(
          parentId,
          TaskOutcome.ofItems(
              RunStatus.failed,
              StatusReason.FOREACH_ITEMS_INVALID,
              "The for-each items were refused when the task fanned out.",
              null));
      return;
    }
    Map<Integer, TaskRunEntity> existing =
        taskRunRepository.findByParentRefOrderByIndexAsc(parentId).stream()
            .collect(Collectors.toMap(TaskRunEntity::getIndex, Function.identity(), (a, b) -> a));
    for (int index = 0; index < items.size(); index++) {
      TaskRunEntity item = existing.get(index);
      if (item == null) {
        try {
          item = taskRunRepository.insert(newItem(parent, index, items.get(index)));
        } catch (DuplicateKeyException created) {
          LOGGER.info("[{}] Item {} already created. Its creator queues it.", parentId, index);
          continue;
        }
      }
      if (RunStatus.notstarted.equals(item.getStatus())
          && RunPhase.pending.equals(item.getPhase())) {
        self.queue(item.getId());
      }
    }
    completeParentIfItemsTerminal(parentId);
  }

  /*
   * An item of a foreach parent: the parent's type, task, spec, workspaces, declared results,
   * timeout, labels and annotations, no dependencies, and the parent's params plus item and index.
   * Its params and spec resolve when it is queued, like any task's.
   */
  private static TaskRunEntity newItem(TaskRunEntity parent, int index, Object element) {
    TaskRunEntity item = new TaskRunEntity();
    item.setName(parent.getName() + "[" + index + "]");
    item.setParentRef(parent.getId());
    item.setIndex(index);
    item.setType(parent.getType());
    item.setStatus(RunStatus.notstarted);
    item.setPhase(RunPhase.pending);
    item.setCreationDate(new Date());
    item.setTaskRef(parent.getTaskRef());
    item.setTaskVersion(parent.getTaskVersion());
    item.setWorkflowRef(parent.getWorkflowRef());
    item.setWorkflowRevisionRef(parent.getWorkflowRevisionRef());
    item.setWorkflowRunRef(parent.getWorkflowRunRef());
    item.setLabels(new HashMap<>(parent.getLabels()));
    item.setAnnotations(new HashMap<>(parent.getAnnotations()));
    item.setTimeout(parent.getTimeout());
    item.setWorkspace(parent.getWorkspace());
    item.setSpec(parent.getSpec());
    item.setResults(
        parent.getResults().stream()
            .map(r -> new RunResult(r.getName(), r.getDescription(), r.getValue()))
            .collect(Collectors.toCollection(LinkedList::new)));
    item.setDependencies(new LinkedList<>());
    List<RunParam> params =
        parent.getParams().stream()
            .filter(p -> !"item".equalsIgnoreCase(p.getName()) && !"index".equalsIgnoreCase(p.getName()))
            .collect(Collectors.toCollection(LinkedList::new));
    // An object or array item resolves and reaches the container as JSON; anything else as text.
    params.add(
        new RunParam(
            "item",
            element,
            (element instanceof Map || element instanceof List ? ParamType.object : ParamType.string)));
    params.add(new RunParam("index", String.valueOf(index), ParamType.string));
    item.setParams(params);
    return item;
  }

  /*
   * Complete a running foreach parent once every item is terminal. It succeeds only when every item
   * succeeded, and each declared result - or, for a task that declares none, each result its items
   * produced - becomes a JSON array in item order, null for an item that did not succeed. The
   * parent ends with that outcome in the completion Compare-And-Set itself, so of any number of
   * concurrent last-item enders exactly one completes it and advances the graph.
   */
  void completeParentIfItemsTerminal(String parentRef) {
    TaskRunEntity parent = taskRunRepository.findById(parentRef).orElse(null);
    if (parent == null
        || !RunPhase.running.equals(parent.getPhase())
        || parent.getForeach() == null
        || !(parent.getForeach().getItems() instanceof List<?> expected)) {
      return;
    }
    // Each item end asks first whether any item is unfinished, and only the last loads them all.
    if (!taskRunService.allItemsCompleted(parentRef, expected.size())) {
      return;
    }
    List<TaskRunEntity> items = taskRunService.findItemOutcomes(parentRef);
    long failed = items.stream().filter(item -> !RunStatus.succeeded.equals(item.getStatus())).count();
    List<RunResult> results = new LinkedList<>();
    for (RunResult declared :
        (parent.getResults().isEmpty() ? producedResults(items) : parent.getResults())) {
      List<Object> values = new ArrayList<>(items.size());
      items.forEach(
          item ->
              values.add(
                  (RunStatus.succeeded.equals(item.getStatus())
                      ? resultValueOf(item, declared.getName())
                      : null)));
      results.add(new RunResult(declared.getName(), declared.getDescription(), values));
    }
    TaskOutcome outcome =
        TaskOutcome.ofItems(
            (failed == 0 ? RunStatus.succeeded : RunStatus.failed),
            (failed == 0 ? null : StatusReason.ITEM_FAILED),
            (failed == 0
                ? "All " + items.size() + " items succeeded."
                : failed + " of " + items.size() + " items did not succeed."),
            results);
    // The combined results cannot be stored, so the task fails without them rather than hang.
    TaskOutcome withoutResults =
        TaskOutcome.ofItems(
            RunStatus.failed,
            StatusReason.RESULTS_TOO_LARGE,
            "The combined results of "
                + items.size()
                + " items exceed MongoDB's 16 MB document limit. Pass large outputs by reference"
                + " (workspace path or URI).",
            null);
    self.end(parentRef, outcome, withoutResults);
  }

  // Whether a write failed because the document, or the command carrying it, is over MongoDB's
  // 16 MB limit - rejected by the driver before sending, or by the server.
  private static boolean isDocumentTooLarge(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof BsonMaximumSizeExceededException
          || (cause instanceof MongoException mongoException
              && DOCUMENT_TOO_LARGE_CODES.contains(mongoException.getCode()))) {
        return true;
      }
    }
    return false;
  }

  // The results a task's items produced, one per name in first-seen item order: what a parent that
  // declares no results (a run-workflow task, whose results are its child's) collects.
  private static List<RunResult> producedResults(List<TaskRunEntity> items) {
    Map<String, RunResult> produced = new LinkedHashMap<>();
    items.forEach(
        item ->
            item.getResults()
                .forEach(
                    result ->
                        produced.putIfAbsent(
                            result.getName(),
                            new RunResult(result.getName(), result.getDescription(), null))));
    return new LinkedList<>(produced.values());
  }

  private static Object resultValueOf(TaskRunEntity taskRun, String name) {
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .findFirst()
        .orElse(null);
  }

  // The item array a resolved foreach value holds: a List as is, or text holding a JSON array (a
  // task result arrives as text). Null for anything else.
  private static List<Object> foreachItemsOf(Object value) {
    if (value instanceof List<?> list) {
      return new ArrayList<>(list);
    }
    if (value instanceof String string) {
      try {
        return (OBJECT_MAPPER.readValue(string, Object.class) instanceof List<?> list
            ? new ArrayList<>(list)
            : null);
      } catch (RuntimeException notJson) {
        return null;
      }
    }
    return null;
  }

  /*
   * Fencing: a request carrying claimant identity must match the current claim, and the claimant
   * must still hold the task - in queued or running, never pending, where a kept claim marks an
   * attempt the engine requeued; a request with no identity is the legacy protocol and is accepted
   * with a log line.
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
            && claimedBy
                .map(
                    by ->
                        by.equals(taskExecution.getClaim().getBy())
                            && !RunPhase.pending.equals(taskExecution.getPhase()))
                .orElse(true)
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

  private void updatePendingApprovalStatus(WorkflowRunEntity wfRunEntity) {
    boolean existingApprovals =
        actionRepository.existsByWorkflowRunRefAndStatus(
            wfRunEntity.getId(), ActionStatus.submitted);
    wfRunEntity.setAwaitingApproval(existingApprovals);
    // Field-scoped write so a level-triggered recompute can never stomp concurrent run state.
    this.workflowRunStateHelper.setAwaitingApproval(wfRunEntity.getId(), existingApprovals);
  }

  private void saveWorkflowStatus(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    String status = ParameterUtil.getValue(taskExecution.getParams(), "status").toString();
    if (!status.isBlank()) {
      RunStatus taskStatus = RunStatus.valueOf(status);
      // Field-scoped write - statusOverride is the ONLY field setwfstatus owns. Saving
      // wfRunEntity whole wrote back the snapshot execute() read at its entry, reverting every
      // field a concurrent Compare-And-Set had committed since: a result pushed by a parallel
      // setwfproperty, isAwaitingApproval, pauseRequestedAt, phase/status, duration.
      this.workflowRunStateHelper.setStatusOverride(wfRunEntity.getId(), taskStatus);
      // Keep the in-memory snapshot consistent with the stored document for the remainder of
      // this execute() call; finishWorkflow re-reads the WorkflowRun for its own decision.
      wfRunEntity.setStatusOverride(taskStatus);
    }
  }

  private void createSleepTask(TaskRunEntity taskExecution) {
    String value = ParameterUtil.getValue(taskExecution.getParams(), "duration").toString();
    long duration = Long.parseLong(value);
    // Durable wait: park as waiting and let the watcher complete it when waitUntil elapses. No
    // held thread survives a crash - the sweep resumes it.
    taskRunService.tryPark(
        taskExecution.getId(), new Date(System.currentTimeMillis() + duration));
  }

  private void processDecision(TaskRunEntity taskExecution) {
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
    taskRunService.tryPark(
        taskExecution.getId(), new Date(System.currentTimeMillis() + LOCK_RETRY_BACKOFF_MILLIS));
    return false;
  }

  private void releaseTaskLock(TaskRunEntity taskExecution) {
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
  // scope is the id of the workspace that owns the run's workflow, so a rename keeps it.
  String scopedLockKey(TaskRunEntity taskExecution) {
    List<RunParam> params = taskExecution.getParams();
    if (!ParameterUtil.containsName(params, "key")) {
      return null;
    }
    String key = ParameterUtil.getValue(params, "key").toString();
    String workspace = paramLayerService.owningWorkspace(taskExecution.getWorkflowRef());
    return (workspace != null) ? workspace + ":" + key : key;
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
    // The wait is over: the run clock runs again while the task completes or re-attempts.
    workflowRunStateHelper.tryResumeWaiting(taskExecution.getWorkflowRunRef());
    if (TaskType.sleep.equals(taskExecution.getType())) {
      taskExecution.setStatus(RunStatus.succeeded);
      self.end(taskRunId, TaskOutcome.executed(taskExecution));
      return;
    }
    if (TaskType.acquirelock.equals(taskExecution.getType())) {
      WorkflowRunEntity wfRunEntity =
          workflowRunRepository.findById(taskExecution.getWorkflowRunRef()).orElse(null);
      if (wfRunEntity == null) {
        return;
      }
      if (acquireTaskLock(taskExecution, wfRunEntity)) {
        self.end(taskRunId, TaskOutcome.executed(taskExecution));
      } else {
        markWaitingIfIdle(wfRunEntity.getId());
      }
    }
  }

  /*
   * Submit a child WorkflowRun. Returns true when the task ends now - a fire-and-forget child, or
   * a failure - and false when it parked as waiting for the child to finish.
   *
   * Lineage is the typed pair the retry path already uses: the child's trigger is "task" and its
   * initiatedByRef is this TaskRun's id. ChildWorkflowRunListener walks it back to end this task,
   * the cascade cancel walks it forward, and the nesting count climbs it.
   */
  private boolean runWorkflow(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    LOGGER.debug("[{}] RunWorkflow Request received.", taskExecution.getId());
    Object workflowRefValue =
        taskExecution.getParams() != null
            ? ParameterUtil.getValue(taskExecution.getParams(), "workflowRef")
            : null;
    if (workflowRefValue == null || workflowRefValue.toString().isBlank()) {
      // Empty is a valid stored value (it may be substituted or deliberately blank) - the
      // requirement belongs to this task, so the failure names the parameter here.
      taskExecution.setStatusMessage(
          "Parameter 'workflowRef' resolved to no value; provide the workflow to run.");
      taskExecution.setStatus(RunStatus.failed);
      return true;
    }

    int cap = maxNestingDepth();
    if (nestingDepth(wfRunEntity, cap) >= cap) {
      taskExecution.setStatusMessage(
          "Child workflow nesting depth of "
              + cap
              + " reached; the child workflow was not submitted.");
      taskExecution.setStatusReason(StatusReason.NESTING_DEPTH_EXCEEDED);
      taskExecution.setStatus(RunStatus.failed);
      return true;
    }

    boolean wait =
        Boolean.parseBoolean(
            String.valueOf(ParameterUtil.getValue(taskExecution.getParams(), "wait")));
    // Park BEFORE submitting: a child that completes immediately must find a parent already
    // waiting, or its transition listener would skip a parent still marked running and the task
    // would sit parked until its timeout.
    if (wait && !taskRunService.tryArmWait(taskExecution.getId())) {
      LOGGER.debug("[{}] RunWorkflow no longer running; not armed.", taskExecution.getId());
    }
    try {
      String workflowRef = workflowRefValue.toString();
      WorkflowSubmitRequest request = new WorkflowSubmitRequest();
      request.setTrigger(TriggerEnum.task);
      request.setParams(childParams(taskExecution, "workflowRef", "wait"));
      request.setAnnotations(wfRunEntity.getAnnotations());
      // The child's own Workflow declares its workspace; the parent's is not handed down.
      request.setTimeout(wfRunEntity.getTimeout());
      request.setRetries(wfRunEntity.getRetries());
      // The child takes the parent run's labels, not the server's. A for-each item's child is
      // labelled with its item, when that is text that carries no secret.
      Map<String, String> labels = ServerLabel.withoutServerLabels(wfRunEntity.getLabels());
      Object item =
          (taskExecution.getParentRef() != null)
              ? ParameterUtil.getValue(taskExecution.getParams(), "item")
              : null;
      if (item instanceof String text
          && !text.isEmpty()
          && !paramManager.carriesSecret(wfRunEntity, taskExecution, text)) {
        labels.put(ServerLabel.ITEM, text);
      }
      request.setLabels(labels);
      request.setDebug(wfRunEntity.getDebug());
      // TODO figure out how to set version
      //        request.setWorkflowVersion();
      LOGGER.debug(
          "[{}] Submitting RunWorkflow Request for ref: {}.", taskExecution.getId(), workflowRef);
      WorkflowRun wfRunResponse =
          workflowService.submit(workflowRef, request, true, taskExecution.getId());
      eventPublisher.publishEvent(new ChildWorkflowRunCreated(workflowRef, wfRunResponse.getId()));
      RunResult childRef = new RunResult("workflowRunRef", wfRunResponse.getId());
      if (wait) {
        // A parked TaskRun never reaches execute()'s save, so the child link is written
        // field-scoped rather than lost.
        ResultUtil.upsertResultByName(
            mongoTemplate, taskExecution.getId(), TaskRunEntity.class, childRef);
        taskExecution.setStatus(RunStatus.waiting);
        return false;
      }
      taskExecution.setResults(new LinkedList<>(List.of(childRef)));
      taskExecution.setStatus(RunStatus.succeeded);
    } catch (Exception ex) {
      LOGGER.error(
          "[{}] Unable to execute RunWorkflow task. Error: {}",
          taskExecution.getId(),
          ex.getMessage());
      taskExecution.setStatusMessage(ex.getMessage());
      taskExecution.setStatus(RunStatus.failed);
    }
    // No save here - the execute() endTask branch persists this same taskExecution, writing the
    // failure over an arm that a failed submit left behind.
    return true;
  }

  /*
   * The params a child run is submitted with: copies of the task's own - the values its node passes,
   * and a for-each item's item and index - minus the ones the task reads itself. The parent run's
   * params are not handed down: a child gets only what its node passes.
   */
  private static List<RunParam> childParams(TaskRunEntity taskExecution, String... ownParams) {
    return taskExecution.getParams().stream()
        .filter(p -> Arrays.stream(ownParams).noneMatch(own -> own.equalsIgnoreCase(p.getName())))
        .map(p -> new RunParam(p.getName(), p.getValue(), p.getType()))
        .collect(Collectors.toCollection(LinkedList::new));
  }

  /*
   * The child nesting cap: the configured value, or the platform default when the key is not
   * seeded (an engine whose database predates it still has a cap).
   */
  private int maxNestingDepth() {
    return workflowRunSetting(MAX_NESTING_DEPTH, DEFAULT_MAX_NESTING_DEPTH);
  }

  // The foreach item cap, with the same fallback.
  private int maxForeachItems() {
    return workflowRunSetting(MAX_FOREACH_ITEMS, DEFAULT_MAX_FOREACH_ITEMS);
  }

  private int workflowRunSetting(String key, int defaultValue) {
    try {
      String value = settingsService.getSettingConfig(WORKFLOWRUN_SETTINGS_KEY, key).getValue();
      return NumberUtils.isDigits(value) ? Integer.parseInt(value) : defaultValue;
    } catch (RuntimeException notConfigured) {
      return defaultValue;
    }
  }

  /*
   * Levels of runworkflow nesting above this run: 0 for a run nothing else started, 1 for the
   * child of a root run, and so on. Each hop is the two lookups the lineage records - the
   * initiating TaskRun, then the WorkflowRun that owns it. The walk stops at the cap, so a broken
   * lineage can never make it unbounded.
   */
  private int nestingDepth(WorkflowRunEntity wfRunEntity, int cap) {
    int depth = 0;
    WorkflowRunEntity current = wfRunEntity;
    while (depth < cap
        && TriggerEnum.task.getTrigger().equals(current.getTrigger())
        && current.getInitiatedByRef() != null) {
      TaskRunEntity initiator =
          taskRunRepository.findById(current.getInitiatedByRef()).orElse(null);
      if (initiator == null) {
        return depth;
      }
      current = workflowRunRepository.findById(initiator.getWorkflowRunRef()).orElse(null);
      if (current == null) {
        return depth;
      }
      depth++;
    }
    return depth;
  }

  private void runScheduledWorkflow(TaskRunEntity taskExecution, WorkflowRunEntity wfRunEntity) {
    Object scheduledRefValue =
        taskExecution.getParams() != null
            ? ParameterUtil.getValue(taskExecution.getParams(), "workflowRef")
            : null;
    if (scheduledRefValue != null && !scheduledRefValue.toString().isBlank()) {
      String workflowId = scheduledRefValue.toString();
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
        // Same two-stage dance as the old Calendar version: the futureIn/futurePeriod addition
        // happens in the JVM's default zone (matching Calendar.getInstance()'s default), then -
        // only for days/weeks/months - the clock time-of-day is overwritten in the target
        // timezone. For minutes/hours periods the "timezone" param is read but never applied,
        // same as before.
        ZonedDateTime executionZdt = executionDate.toInstant().atZone(ZoneId.systemDefault());
        ChronoUnit unit =
            switch (futurePeriod) {
              case "hours" -> ChronoUnit.HOURS;
              case "days" -> ChronoUnit.DAYS;
              case "weeks" -> ChronoUnit.WEEKS;
              case "months" -> ChronoUnit.MONTHS;
              default -> ChronoUnit.MINUTES;
            };
        executionZdt = executionZdt.plus(futureIn, unit);

        if (!futurePeriod.equals("minutes") && !futurePeriod.equals("hours")) {
          // 24-hour parse (fixes the v4 bug: Calendar.HOUR is the 0-11 field, so time=14:30 was
          // silently read back as 02:30) - still tolerant of a single-digit hour ("9:30").
          LocalTime clockTime = LocalTime.parse(time, RUN_SCHEDULED_TIME_FORMATTER);
          ZoneId targetZone;
          try {
            targetZone = ZoneId.of(timezone);
          } catch (DateTimeException ex) {
            // TimeZone.getTimeZone(invalid) silently fell back to GMT rather than throwing.
            targetZone = ZoneOffset.UTC;
          }
          LOGGER.debug("With time to be set to: " + time + " in " + timezone);
          executionZdt =
              executionZdt
                  .withZoneSameInstant(targetZone)
                  .withHour(clockTime.getHour())
                  .withMinute(clockTime.getMinute());
          LOGGER.debug("With execution set to: " + executionZdt + " in " + timezone);
        }
        LOGGER.debug("With execution set to: " + executionZdt + " in UTC");

        // The scheduled run gets what the node passes, minus the params this task reads itself.
        List<RunParam> newParamList =
            childParams(
                taskExecution, "workflowRef", "wait", "futureIn", "futurePeriod", "timezone", "time");

        // Define and create the schedule
        WorkflowSchedule schedule = new WorkflowSchedule();
        schedule.setWorkflowRef(workflowId);
        schedule.setName(taskExecution.getName());
        schedule.setDescription(
            "This schedule was generated through a Run Scheduled Workflow task.");
        schedule.setParams(newParamList);
        schedule.setDateSchedule(Date.from(executionZdt.toInstant()));
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
    // Field-scoped arm, then re-read: an event that arrived since execute()'s entry read has
    // already set preApprovedStatus and results through applyEventDelivery and must not be lost.
    if (!taskRunService.tryArmWait(taskExecution.getId())) {
      LOGGER.debug("[{}] Eventwait no longer running; not armed.", taskExecution.getId());
    }
    TaskRunEntity armed = taskRunRepository.findById(taskExecution.getId()).orElse(taskExecution);
    taskExecution.setStatus(RunStatus.waiting);
    taskExecution.setPreApprovedStatus(armed.getPreApprovedStatus());
    taskExecution.setResults(armed.getResults());

    if (taskExecution.getPreApprovedStatus() != null) {
      taskExecution.setStatus(taskExecution.getPreApprovedStatus());
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

        // The approval task declares numberOfApprovals; numberOfApprovers, the name read before,
        // is still taken from a definition that sets it.
        List<RunParam> params = taskExecution.getParams();
        Object numberOfApprovals =
            Stream.of("numberOfApprovals", "numberOfApprovers")
                .map(name -> ParameterUtil.getValue(params, name))
                .filter(value -> value != null && !value.toString().isBlank())
                .findFirst()
                .orElse(null);
        if (numberOfApprovals != null) {
          actionEntity.setNumberOfApprovers(Integer.valueOf(numberOfApprovals.toString().trim()));
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
    workflowRunStateHelper.setAwaitingApproval(wfRunEntity.getId(), true);
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
    workflowRunStateHelper.appendResult(wfRunEntity.getId(), wfResult);
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
    // Execution time, read from the run as it is now so a stop still open is excluded.
    long duration =
        RunTimeouts.executionMillis(
            workflowRunRepository.findById(wfRunEntity.getId()).orElse(wfRunEntity), new Date());

    // Completion Compare-And-Set: running becomes completed exactly once, so racing advances
    // (or a concurrent cancel/timeout) can never complete the run twice or stomp its status.
    if (workflowRunStateHelper.tryComplete(
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
}
