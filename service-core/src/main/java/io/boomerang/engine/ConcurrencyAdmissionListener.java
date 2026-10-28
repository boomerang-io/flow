package io.boomerang.engine;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.model.WorkflowRunTransition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Re-admit the run that has waited longest for a slot of a workflow's concurrency limit when a
 * run of that workflow ends. The completion freed the slot; admission re-checks the count, so a
 * second completion racing this one re-admits nothing extra. The watcher's parked-run sweep is the
 * fallback for a completion whose re-admission was lost with the instance.
 */
@Component
public class ConcurrencyAdmissionListener {

  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final WorkflowExecutionService workflowExecutionService;

  public ConcurrencyAdmissionListener(
      WorkflowRunStateHelper workflowRunStateHelper,
      WorkflowExecutionService workflowExecutionService) {
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.workflowExecutionService = workflowExecutionService;
  }

  @EventListener
  public void onWorkflowRunTransition(WorkflowRunTransition transition) {
    if (RunPhase.completed != transition.toPhase()
        || RunPhase.completed == transition.fromPhase()
        || transition.workflowRef() == null) {
      return;
    }
    WorkflowRunEntity parked = workflowRunStateHelper.findOldestParked(transition.workflowRef());
    if (parked == null) {
      return;
    }
    // Best effort: a run's own completion must never fail because the next one could not be
    // re-admitted; the sweep re-drives it.
    try {
      LOGGER.info(
          "[{}] A run of the workflow ended. Re-admitting the run parked longest for a slot.",
          parked.getId());
      workflowExecutionService.readmit(parked.getId());
    } catch (Exception ex) {
      LOGGER.error("[{}] Unable to re-admit the parked run.", parked.getId(), ex);
    }
  }
}
