package io.boomerang.engine;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.engine.entity.EventOutboxEntity;
import io.boomerang.engine.model.TaskRunTransition;
import io.boomerang.engine.model.WorkflowRunTransition;
import io.boomerang.engine.repository.EventOutboxRepository;
import java.util.Date;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridge from the winner-published domain transition events to the CloudEvents egress: each
 * externally-visible transition becomes one outbox row that the dispatcher delivers. The bean
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
    if (emits(
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
