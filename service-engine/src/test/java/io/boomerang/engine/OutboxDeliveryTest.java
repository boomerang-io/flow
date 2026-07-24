package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.entity.EventOutboxEntity;
import io.boomerang.engine.enums.OutboxStatus;
import io.boomerang.engine.model.TaskRunTransition;
import io.boomerang.engine.repository.EventOutboxRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The outbox contract: a real status change writes exactly one pending row, a phase-only claim
 * transition writes none, and the dispatcher delivers and marks rows sent via a Compare-And-Set.
 * The bridge and dispatcher beans are conditional on the sink being enabled, so both are
 * exercised by direct construction here.
 */
class OutboxDeliveryTest extends AbstractEngineIntegrationTest {

  @Autowired private EventOutboxRepository eventOutboxRepository;
  @Autowired private EventSinkService eventSinkService;

  @Test
  void statusChangeWritesOneRowAndPhaseOnlyWritesNone() {
    CloudEventsBridge bridge = new CloudEventsBridge(eventOutboxRepository);
    WorkflowRunEntity wfRun =
        savedWorkflowRun("outbox-emit-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "emitting",
            TaskType.template,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    // Real status change - exactly one pending row.
    bridge.onTaskRunTransition(
        new TaskRunTransition(
            taskRun.getId(),
            wfRun.getId(),
            RunStatus.ready,
            RunPhase.queued,
            RunStatus.running,
            RunPhase.running));
    List<EventOutboxEntity> rows = rowsFor(taskRun.getId());
    assertEquals(1, rows.size(), "a status change writes exactly one outbox row");
    assertEquals(OutboxStatus.pending, rows.get(0).getStatus());
    assertEquals(RunStatus.ready, rows.get(0).getFrom().status());
    assertEquals(RunStatus.running, rows.get(0).getTo().status());

    // Phase-only claim transition (non-terminal) - silent.
    bridge.onTaskRunTransition(
        new TaskRunTransition(
            taskRun.getId(),
            wfRun.getId(),
            RunStatus.ready,
            RunPhase.pending,
            RunStatus.ready,
            RunPhase.queued));
    assertEquals(
        1, rowsFor(taskRun.getId()).size(), "a phase-only claim transition writes no outbox row");
  }

  @Test
  void dispatcherDeliversPendingRowAndMarksItSent() {
    CloudEventsBridge bridge = new CloudEventsBridge(eventOutboxRepository);
    OutboxDispatcher dispatcher =
        new OutboxDispatcher(
            eventOutboxRepository, taskRunRepository, workflowRunRepository, eventSinkService);
    WorkflowRunEntity wfRun =
        savedWorkflowRun("outbox-send-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "sending",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    bridge.onTaskRunTransition(
        new TaskRunTransition(
            taskRun.getId(),
            wfRun.getId(),
            RunStatus.running,
            RunPhase.running,
            RunStatus.succeeded,
            RunPhase.completed));
    String rowId = rowsFor(taskRun.getId()).get(0).getId();

    dispatcher.drain();

    EventOutboxEntity sent = eventOutboxRepository.findById(rowId).orElseThrow();
    assertEquals(OutboxStatus.sent, sent.getStatus());
    assertNotNull(sent.getSentAt());
    assertTrue(
        eventOutboxRepository.findDeliverable(new java.util.Date(), 100).stream()
            .noneMatch(r -> rowId.equals(r.getId())),
        "a sent row is never redelivered");
  }

  private List<EventOutboxEntity> rowsFor(String ref) {
    return eventOutboxRepository.findAll().stream().filter(r -> ref.equals(r.getRef())).toList();
  }
}
