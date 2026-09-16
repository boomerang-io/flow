package io.boomerang.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.model.TaskRunTransition;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.enums.OutboxStatus;
import io.boomerang.event.repository.EventOutboxRepository;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The outbox contract: a real status change writes exactly one pending row, a phase-only claim
 * transition writes none, and the dispatcher delivers and marks rows sent via a Compare-And-Set.
 * The bridge and dispatcher beans are conditional on the sink being enabled, so both are
 * exercised by direct construction here. A row whose run no longer exists is the failure
 * fixture: delivery throws, so the row records why and, once its budget is spent, when it died.
 */
class OutboxDeliveryTest extends AbstractEngineIntegrationTest {

  // What OutboxDispatcher.describe() makes of the missing-run failure the undeliverable rows hit.
  private static final String UNDELIVERABLE_ERROR =
      "IllegalStateException: TaskRun no longer exists";

  @Autowired private EventOutboxRepository eventOutboxRepository;
  @Autowired private EventSinkService eventSinkService;
  @Autowired private MongoTemplate mongoTemplate;

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
    OutboxDispatcher dispatcher = dispatcher();
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
        dispatcher.findDeliverable(new java.util.Date(), 100).stream()
            .noneMatch(r -> rowId.equals(r.getId())),
        "a sent row is never redelivered");
  }

  /*
   * A row that exhausted its attempts is kept, not dropped, and the operator route is the way back:
   * replay puts it in the queue with a fresh budget and the next drain delivers it.
   */
  @Test
  void aDeadRowIsReplayedAndThenDelivered() {
    CloudEventsBridge bridge = new CloudEventsBridge(eventOutboxRepository);
    OutboxDispatcher dispatcher = dispatcher();
    OutboxService outboxService = new OutboxService(mongoTemplate);
    WorkflowRunEntity wfRun =
        savedWorkflowRun("outbox-replay-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            "replaying",
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
    EventOutboxEntity row = rowsFor(taskRun.getId()).get(0);
    row.setStatus(OutboxStatus.dead);
    row.setAttempts(3);
    eventOutboxRepository.save(row);

    assertTrue(
        outboxService.query(OutboxStatus.dead, 0, 100).getContent().stream()
            .anyMatch(r -> row.getId().equals(r.getId())),
        "the dead row must be listable before it is replayed");

    assertEquals(1, outboxService.replay(List.of(row.getId()), OutboxStatus.dead, null));

    EventOutboxEntity replayed = eventOutboxRepository.findById(row.getId()).orElseThrow();
    assertEquals(OutboxStatus.pending, replayed.getStatus());
    assertEquals(0, replayed.getAttempts());

    dispatcher.drain();

    assertEquals(
        OutboxStatus.sent,
        eventOutboxRepository.findById(row.getId()).orElseThrow().getStatus(),
        "the next drain delivers the replayed row");
  }

  /*
   * A row the operator route can explain: the delivery error is recorded on the row, not only in
   * the log, and the latest attempt's failure is the one kept.
   */
  @Test
  void aFailedDeliveryRecordsTheErrorAndKeepsTheLatestAcrossAttempts() {
    OutboxDispatcher dispatcher = dispatcher();
    EventOutboxEntity row = undeliverableRow("outbox-error-1");
    row.setLastError("a stale failure from an earlier attempt");
    eventOutboxRepository.save(row);

    dispatcher.drain();

    EventOutboxEntity first = eventOutboxRepository.findById(row.getId()).orElseThrow();
    assertEquals(1, first.getAttempts());
    assertEquals(UNDELIVERABLE_ERROR, first.getLastError(), "the latest failure replaces the last");
    assertNull(first.getDeadAt(), "a row still inside its retry budget has not given up");

    // The backoff would hold the row back until the next window - the point here is the second
    // attempt's record, not the wait.
    first.getRetry().setAfter(new Date(0));
    eventOutboxRepository.save(first);

    dispatcher.drain();

    EventOutboxEntity second = eventOutboxRepository.findById(row.getId()).orElseThrow();
    assertEquals(2, second.getAttempts());
    assertEquals(UNDELIVERABLE_ERROR, second.getLastError());
    assertNull(second.getDeadAt());
  }

  /*
   * The dead row is the one an operator has to act on, so it carries both why it failed and when
   * it gave up - and the list route hands both over.
   */
  @Test
  void aRowThatExhaustsItsBudgetRecordsWhenItGaveUpAndTheListRouteShowsWhy() {
    OutboxDispatcher dispatcher = dispatcher();
    EventOutboxEntity row = undeliverableRow("outbox-error-dead");
    row.setAttempts(2); // one attempt left of the three
    eventOutboxRepository.save(row);

    dispatcher.drain();

    EventOutboxEntity dead = eventOutboxRepository.findById(row.getId()).orElseThrow();
    assertEquals(OutboxStatus.dead, dead.getStatus());
    assertEquals(UNDELIVERABLE_ERROR, dead.getLastError());
    assertNotNull(dead.getDeadAt(), "a dead row records when it gave up");

    EventOutboxEntity listed =
        new OutboxService(mongoTemplate).query(OutboxStatus.dead, 0, 100).getContent().stream()
            .filter(r -> row.getId().equals(r.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals(UNDELIVERABLE_ERROR, listed.getLastError());
    assertNotNull(listed.getDeadAt());
  }

  /* Requeued means requeued: the previous failure no longer describes the row. */
  @Test
  void replayClearsTheFailureDetail() {
    EventOutboxEntity row = undeliverableRow("outbox-error-replayed");
    row.setStatus(OutboxStatus.dead);
    row.setAttempts(3);
    row.setLastError(UNDELIVERABLE_ERROR);
    row.setDeadAt(new Date());
    eventOutboxRepository.save(row);

    assertEquals(
        1, new OutboxService(mongoTemplate).replay(List.of(row.getId()), OutboxStatus.dead, null));

    EventOutboxEntity replayed = eventOutboxRepository.findById(row.getId()).orElseThrow();
    assertEquals(OutboxStatus.pending, replayed.getStatus());
    assertNull(replayed.getLastError(), "a requeued row carries no failure");
    assertNull(replayed.getDeadAt(), "a requeued row has not given up");
  }

  private OutboxDispatcher dispatcher() {
    return new OutboxDispatcher(
        mongoTemplate, taskRunRepository, workflowRunRepository, eventSinkService);
  }

  /*
   * A row whose run no longer exists - the payload source of truth is gone, so delivery fails the
   * same way a sink outage does, without a mock in the way.
   */
  private EventOutboxEntity undeliverableRow(String ref) {
    EventOutboxEntity row = new EventOutboxEntity();
    row.setRefType(EventOutboxEntity.REF_TYPE_TASKRUN);
    row.setRef(ref);
    row.setOccurredAt(new Date());
    row.setFrom(new EventOutboxEntity.RunState(RunStatus.running, RunPhase.running));
    row.setTo(new EventOutboxEntity.RunState(RunStatus.succeeded, RunPhase.completed));
    return row;
  }

  private List<EventOutboxEntity> rowsFor(String ref) {
    return eventOutboxRepository.findAll().stream().filter(r -> ref.equals(r.getRef())).toList();
  }
}
