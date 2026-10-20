package io.boomerang.event;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.common.model.TaskRunTransition;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.event.repository.EventOutboxRepository;
import java.util.Date;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridge from the winner-published domain transition events to the CloudEvents egress: each
 * externally-visible transition becomes one outbox row that the dispatcher delivers. The end node
 * emits nothing, as the start node never does: the run's own completion event reports it. The bean
 * only exists when the sink is enabled, so a disabled sink never accumulates pending rows.
 */
@Component
@ConditionalOnProperty(
    name = "flow.events.sink.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class CloudEventsBridge {

  private final EventOutboxRepository eventOutboxRepository;

  public CloudEventsBridge(EventOutboxRepository eventOutboxRepository) {
    this.eventOutboxRepository = eventOutboxRepository;
  }

  @EventListener
  public void onTaskRunTransition(TaskRunTransition transition) {
    if (!TaskType.end.equals(transition.type())
        && !endsBeforeCompleting(transition)
        && emits(
        transition.fromStatus(),
        transition.fromPhase(),
        transition.toStatus(),
        transition.toPhase())) {
      EventOutboxEntity row = new EventOutboxEntity();
      row.setRefType(EventOutboxEntity.REF_TYPE_TASKRUN);
      row.setRef(transition.id());
      row.setFrom(new EventOutboxEntity.RunState(transition.fromStatus(), transition.fromPhase()));
      row.setTo(new EventOutboxEntity.RunState(transition.toStatus(), transition.toPhase()));
      row.setOccurredAt(new Date());
      row.setRouting(new EventOutboxEntity.Routing(null, transition.workflowRunRef()));
      eventOutboxRepository.insert(row);
    }
  }

  @EventListener
  public void onWorkflowRunTransition(WorkflowRunTransition transition) {
    if (emits(
        transition.fromStatus(),
        transition.fromPhase(),
        transition.toStatus(),
        transition.toPhase())) {
      EventOutboxEntity row = new EventOutboxEntity();
      row.setRefType(EventOutboxEntity.REF_TYPE_WORKFLOWRUN);
      row.setRef(transition.id());
      row.setFrom(new EventOutboxEntity.RunState(transition.fromStatus(), transition.fromPhase()));
      row.setTo(new EventOutboxEntity.RunState(transition.toStatus(), transition.toPhase()));
      row.setOccurredAt(new Date());
      row.setRouting(new EventOutboxEntity.Routing(transition.workflowRef(), transition.id()));
      eventOutboxRepository.insert(row);
    }
  }

  // A transition emits when the externally-visible status changes, or when the run reaches the
  // terminal phase carrying a caller-persisted status (whose change was never emitted). Claim
  // transitions (phase-only, non-terminal) stay silent.
  // A skip or an admission rejection is two writes: the final status while still pending
  // (TaskRunService.trySkip, tryReject), then end()'s completion. Only the completion is an event,
  // so one change of state sends one.
  private static boolean endsBeforeCompleting(TaskRunTransition transition) {
    return RunPhase.pending == transition.toPhase()
        && (RunStatus.skipped == transition.toStatus()
            || RunStatus.failed == transition.toStatus()
            || RunStatus.invalid == transition.toStatus());
  }

  private static boolean emits(
      RunStatus fromStatus, RunPhase fromPhase, RunStatus toStatus, RunPhase toPhase) {
    return fromStatus != toStatus || (fromPhase != toPhase && RunPhase.completed == toPhase);
  }
}
