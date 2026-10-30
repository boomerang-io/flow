package io.boomerang.event;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.event.config.EventSinkProperties;
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
 * only exists when the sink is enabled, so a disabled sink never accumulates pending rows, and a
 * transition no configured sink's filter wants writes no row.
 */
@Component
@ConditionalOnProperty(
    name = "flow.events.sink.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class CloudEventsBridge {

  private final EventOutboxRepository eventOutboxRepository;
  private final EventSinkProperties properties;

  public CloudEventsBridge(
      EventOutboxRepository eventOutboxRepository, EventSinkProperties properties) {
    this.eventOutboxRepository = eventOutboxRepository;
    this.properties = properties;
  }

  @EventListener
  public void onTaskRunTransition(TaskRunTransition transition) {
    if (!TaskType.end.equals(transition.type())
        && emits(
        transition.fromStatus(),
        transition.fromPhase(),
        transition.toStatus(),
        transition.toPhase())
        && properties.admits(
            EventOutboxEntity.REF_TYPE_TASKRUN, transition.toStatus(), transition.toPhase())) {
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
        transition.toPhase())
        && properties.admits(
            EventOutboxEntity.REF_TYPE_WORKFLOWRUN, transition.toStatus(), transition.toPhase())) {
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
  private static boolean emits(
      RunStatus fromStatus, RunPhase fromPhase, RunStatus toStatus, RunPhase toPhase) {
    return fromStatus != toStatus || (fromPhase != toPhase && RunPhase.completed == toPhase);
  }
}
