package io.boomerang.engine;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunTransition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Admit the runs waiting for a slot when a run ends: the completion freed a slot of its workflow's
 * concurrency limit and of its workspace's concurrent runs, and the queue it belongs to is drained
 * oldest first. Admission re-checks both counts, so a second completion racing this one admits
 * nothing extra. The watcher's parked-run sweep is the fallback for a completion whose drain was
 * lost with the instance.
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
    // A run that ends before it was admitted, or while parked, held no slot to free.
    if (RunStatus.notstarted == transition.fromStatus()
        || RunStatus.waiting == transition.fromStatus()) {
      return;
    }
    // Most completions find nothing waiting anywhere: one narrow read, and no more.
    if (!workflowRunStateHelper.existsParked()) {
      return;
    }
    // Best effort: a run's own completion must never fail because the next one could not be
    // re-admitted; the sweep re-drives it.
    try {
      workflowExecutionService.drain(transition.workflowRef());
    } catch (Exception ex) {
      LOGGER.error(
          "[{}] Unable to admit the runs waiting behind the completed run.", transition.id(), ex);
    }
  }
}
