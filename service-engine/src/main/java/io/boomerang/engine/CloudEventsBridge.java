package io.boomerang.engine;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.engine.model.TaskRunTransition;
import io.boomerang.engine.model.WorkflowRunTransition;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridge from the winner-published domain transition events to the CloudEvents egress. The one
 * listener on the domain events: it re-reads the document by id and hands it to the existing
 * {@code EventSinkService}. Replaces the deleted save()-interceptor aspects.
 */
@Component
@ConditionalOnProperty(
    name = "flow.events.sink.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class CloudEventsBridge {

  private final TaskRunRepository taskRunRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final EventSinkService eventSinkService;

  public CloudEventsBridge(
      TaskRunRepository taskRunRepository,
      WorkflowRunRepository workflowRunRepository,
      EventSinkService eventSinkService) {
    this.taskRunRepository = taskRunRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.eventSinkService = eventSinkService;
  }

  @EventListener
  public void onTaskRunTransition(TaskRunTransition transition) {
    if (emits(
        transition.fromStatus(),
        transition.fromPhase(),
        transition.toStatus(),
        transition.toPhase())) {
      taskRunRepository
          .findById(transition.id())
          .ifPresent(eventSinkService::publishStatusCloudEvent);
    }
  }

  @EventListener
  public void onWorkflowRunTransition(WorkflowRunTransition transition) {
    if (emits(
        transition.fromStatus(),
        transition.fromPhase(),
        transition.toStatus(),
        transition.toPhase())) {
      workflowRunRepository
          .findById(transition.id())
          .ifPresent(eventSinkService::publishStatusCloudEvent);
    }
  }

  // A transition emits when the externally-visible status changes, or when the run reaches a
  // terminal phase carrying a caller-persisted status (whose change was never emitted). Claim
  // transitions (phase-only, non-terminal) stay silent.
  private static boolean emits(
      RunStatus fromStatus, RunPhase fromPhase, RunStatus toStatus, RunPhase toPhase) {
    return fromStatus != toStatus
        || (fromPhase != toPhase
            && (RunPhase.completed == toPhase || RunPhase.finalized == toPhase));
  }
}
