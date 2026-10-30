package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.workflow.WorkflowRunService;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Child workflow composition, driven off the WorkflowRun transition events rather than a call back
 * into a waiting thread: a completed run ends the {@code runworkflow} task parked on it, and a
 * cancelled or timed out run takes its still in-flight children down with it.
 *
 * <p>Both directions read the lineage the child already carries - trigger {@code task} plus {@code
 * initiatedByRef} holding the submitting TaskRun's id - so neither needs a new field.
 *
 * <p>Ending the parent goes through the same wire-level end the dispatcher calls, so the completion
 * Compare-And-Set applies: a listener that fires twice, or one racing the parent's own timeout,
 * ends the task once. Cancelling a child republishes its own transition, so a chain of nested runs
 * unwinds from the top without this method recursing itself.
 */
@Component
public class ChildWorkflowRunListener {

  private static final Logger LOGGER = LogManager.getLogger();

  // The typed cause written on a parent task whose child run did not succeed.

  private static final List<RunPhase> IN_FLIGHT_PHASES =
      List.of(RunPhase.pending, RunPhase.queued, RunPhase.running);

  private final TaskRunService taskRunService;
  private final TaskRunRepository taskRunRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRunService workflowRunService;
  private final TaskExecutionService taskExecutionService;

  public ChildWorkflowRunListener(
      TaskRunService taskRunService,
      TaskRunRepository taskRunRepository,
      WorkflowRunRepository workflowRunRepository,
      WorkflowRunService workflowRunService,
      @Lazy TaskExecutionService taskExecutionService) {
    this.taskRunService = taskRunService;
    this.taskRunRepository = taskRunRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRunService = workflowRunService;
    this.taskExecutionService = taskExecutionService;
  }

  @EventListener
  public void onWorkflowRunTransition(WorkflowRunTransition transition) {
    if (RunPhase.completed != transition.toPhase()
        || RunPhase.completed == transition.fromPhase()) {
      return;
    }
    WorkflowRunEntity run = workflowRunRepository.findById(transition.id()).orElse(null);
    if (run == null) {
      return;
    }
    // Best effort in both directions: a run's own completion must never fail because its parent
    // task or one of its children could not be reached.
    try {
      endWaitingParentTask(run);
    } catch (Exception ex) {
      LOGGER.error("[{}] Unable to end the parent task of a child run.", run.getId(), ex);
    }
    try {
      cancelInFlightChildren(run);
    } catch (Exception ex) {
      LOGGER.error("[{}] Unable to cancel the children of a completed run.", run.getId(), ex);
    }
    // A completed run holds no lock: whatever it acquired and never released goes with it, so
    // the next acquire on the key succeeds at once instead of waiting out the lease.
    try {
      taskExecutionService.releaseLocksHeldBy(run.getId());
    } catch (Exception ex) {
      LOGGER.error("[{}] Unable to release the locks of a completed run.", run.getId(), ex);
    }
  }

  /*
   * End the runworkflow task parked on this run, carrying the run's own outcome: a succeeded child
   * succeeds the task and hands it the run's results beside the workflowRunRef it already holds;
   * any other terminal status fails it with the ChildRunFailed reason and copies nothing. Only a
   * task still parked as waiting is ended - a fire-and-forget parent (wait=false) already ended
   * itself, and a parent that timed out first is terminal. An item of a for-each task is a task
   * run of its own, so it is found and ended exactly like a plain one.
   */
  private void endWaitingParentTask(WorkflowRunEntity childRun) {
    if (!TriggerEnum.task.getTrigger().equals(childRun.getTrigger())
        || childRun.getInitiatedByRef() == null) {
      return;
    }
    TaskRunEntity parentTask =
        taskRunRepository.findById(childRun.getInitiatedByRef()).orElse(null);
    if (parentTask == null || RunStatus.waiting != parentTask.getStatus()) {
      return;
    }
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    if (RunStatus.succeeded == childRun.getStatus()) {
      endRequest.setStatus(RunStatus.succeeded);
      childRun
          .getResults()
          .forEach(
              result ->
                  endRequest
                      .getResults()
                      .add(
                          new RunResult(
                              result.getName(), result.getDescription(), result.getValue())));
    } else {
      endRequest.setStatus(RunStatus.failed);
      endRequest.setStatusMessage(
          "Child run " + childRun.getId() + " ended " + childRun.getStatus().getStatus());
      endRequest.setStatusReason(StatusReason.CHILD_RUN_FAILED);
    }
    LOGGER.info(
        "[{}] Child run {} ended {}. Ending the waiting parent task.",
        parentTask.getId(),
        childRun.getId(),
        childRun.getStatus());
    taskRunService.end(parentTask.getId(), Optional.of(endRequest));
  }

  /*
   * Cancel the children a cancelled or timed out run left in flight. A pause is deliberately not
   * cascaded: pause is an admission flag on one run, so a paused parent leaves a running child
   * alone.
   */
  private void cancelInFlightChildren(WorkflowRunEntity parentRun) {
    if (RunStatus.cancelled != parentRun.getStatus()
        && RunStatus.timedout != parentRun.getStatus()) {
      return;
    }
    List<String> taskRunIds =
        taskRunRepository.findByWorkflowRunRef(parentRun.getId()).stream()
            .map(TaskRunEntity::getId)
            .toList();
    if (taskRunIds.isEmpty()) {
      return;
    }
    for (WorkflowRunEntity child :
        workflowRunRepository.findByInitiatedByRefInAndPhaseIn(taskRunIds, IN_FLIGHT_PHASES)) {
      LOGGER.info(
          "[{}] Cancelling child of WorkflowRun {}, which ended {}.",
          child.getId(),
          parentRun.getId(),
          parentRun.getStatus());
      workflowRunService.cancel(child.getId());
    }
  }
}
