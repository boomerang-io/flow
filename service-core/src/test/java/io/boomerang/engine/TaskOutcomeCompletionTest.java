package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.TaskRunTransition;
import io.boomerang.engine.model.TaskOutcome;
import io.boomerang.event.CloudEventsBridge;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.repository.EventOutboxRepository;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The engine ends a task it decided the end of - a skip, an admission refusal, an engine-run
 * task's own result - in one completion Compare-And-Set that carries the outcome, so each such end
 * is one write and one published transition, and a task that has moved on is left alone.
 */
@RecordApplicationEvents
class TaskOutcomeCompletionTest extends AbstractEngineIntegrationTest {

  @Autowired private ApplicationEvents events;
  @Autowired private EventOutboxRepository eventOutboxRepository;

  @Test
  void aSkipCompletesInOneWriteWithOneTransitionAndOneOutboxRow() {
    TaskRunEntity task = task("outcome-skip", RunStatus.notstarted, RunPhase.pending);

    assertNotNull(taskRunService.tryComplete(task.getId(), TaskOutcome.skipped(), 0));

    TaskRunEntity after = taskRunRepository.findById(task.getId()).orElseThrow();
    assertEquals(RunStatus.skipped, after.getStatus());
    assertEquals(RunPhase.completed, after.getPhase());
    List<TaskRunTransition> transitions = transitionsOf(task.getId());
    assertEquals(1, transitions.size(), "one transition for the skip");
    assertEquals(task.getWorkflowRunRef(), transitions.get(0).workflowRunRef());
    assertEquals(TaskType.template, transitions.get(0).type());
    assertEquals(RunStatus.notstarted, transitions.get(0).fromStatus());
    assertEquals(RunPhase.pending, transitions.get(0).fromPhase());
    assertEquals(RunStatus.skipped, transitions.get(0).toStatus());
    assertEquals(RunPhase.completed, transitions.get(0).toPhase());

    CloudEventsBridge bridge = new CloudEventsBridge(eventOutboxRepository);
    transitions.forEach(bridge::onTaskRunTransition);
    List<EventOutboxEntity> rows =
        eventOutboxRepository.findAll().stream().filter(r -> task.getId().equals(r.getRef())).toList();
    assertEquals(1, rows.size(), "one outbox row for the skip");
  }

  @Test
  void aRefusalCarriesItsReasonAndMessageInTheSameWrite() {
    TaskRunEntity task = task("outcome-refused", RunStatus.notstarted, RunPhase.pending);

    assertNotNull(
        taskRunService.tryComplete(
            task.getId(),
            TaskOutcome.refused(
                RunStatus.failed, StatusReason.PARAM_UNRESOLVED, "Task 'x': no such param 'y'"),
            0));

    TaskRunEntity after = taskRunRepository.findById(task.getId()).orElseThrow();
    assertEquals(RunStatus.failed, after.getStatus());
    assertEquals(RunPhase.completed, after.getPhase());
    assertEquals(StatusReason.PARAM_UNRESOLVED, after.getStatusReason());
    assertEquals("Task 'x': no such param 'y'", after.getStatusMessage());
    assertEquals(1, transitionsOf(task.getId()).size());
  }

  @Test
  void anOutcomeForATaskThatMovedOnChangesNothing() {
    // Admitted by another caller - a join queued by both parents - so a skip must not touch it.
    TaskRunEntity task = task("outcome-admitted", RunStatus.ready, RunPhase.pending);

    assertNull(taskRunService.tryComplete(task.getId(), TaskOutcome.skipped(), 0));

    TaskRunEntity after = taskRunRepository.findById(task.getId()).orElseThrow();
    assertEquals(RunStatus.ready, after.getStatus());
    assertEquals(RunPhase.pending, after.getPhase());
    assertTrue(transitionsOf(task.getId()).isEmpty());
  }

  @Test
  void aRecordedTimeoutWinsOverAnExecutedOutcome() {
    TaskRunEntity task = task("outcome-timedout", RunStatus.timedout, RunPhase.running);
    task.setStatus(RunStatus.succeeded);

    assertNull(taskRunService.tryComplete(task.getId(), TaskOutcome.executed(task), 0));

    TaskRunEntity after = taskRunRepository.findById(task.getId()).orElseThrow();
    assertEquals(RunStatus.timedout, after.getStatus());
    assertEquals(RunPhase.running, after.getPhase());
  }

  @Test
  void anExecutedOutcomeWritesItsResultsAndDecisionValue() {
    TaskRunEntity task = task("outcome-executed", RunStatus.running, RunPhase.running);
    task.setStatus(RunStatus.succeeded);
    task.setDecisionValue("yes");
    task.setResults(new LinkedList<>(List.of(new RunResult("answer", "42"))));

    assertNotNull(taskRunService.tryComplete(task.getId(), TaskOutcome.executed(task), 0));

    TaskRunEntity after = taskRunRepository.findById(task.getId()).orElseThrow();
    assertEquals(RunStatus.succeeded, after.getStatus());
    assertEquals(RunPhase.completed, after.getPhase());
    assertEquals("yes", after.getDecisionValue());
    assertEquals("42", after.getResults().get(0).getValue());
    assertEquals(1, transitionsOf(task.getId()).size());
  }

  private TaskRunEntity task(String tag, RunStatus status, RunPhase phase) {
    WorkflowRunEntity wfRun = savedWorkflowRun(tag + "-wf", RunStatus.running, RunPhase.running);
    return savedTaskRun(
        tag, TaskType.template, status, phase, wfRun.getWorkflowRef(), wfRun.getId());
  }

  private List<TaskRunTransition> transitionsOf(String id) {
    return events.stream(TaskRunTransition.class).filter(t -> id.equals(t.id())).toList();
  }
}
