package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.boomerang.event.WebhookEventService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code WorkflowRunService.event} delivery idempotency. A delivery that carries an id is recorded in
 * the {@code events_inbox} ledger keyed {@code "<runId>:<eventId>"}, so a redelivery is acknowledged
 * and not applied again; the wait-for-event callback passes its optional {@code eventId} through. A
 * delivery without an id is applied each time, and converges because results merge by name.
 */
class EventDeliveryIdempotencyTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WebhookEventService webhookEventService;

  /** Builds the request as a callback without an {@code eventId} does: topic, status and results. */
  private static WorkflowRunEventRequest webhookDelivery(String topic) {
    WorkflowRunEventRequest request = new WorkflowRunEventRequest();
    request.setTopic(topic);
    request.setStatus(RunStatus.succeeded);
    request.setResults(List.of(new RunResult("data", "{\"build\":\"ok\"}")));
    return request;
  }

  private TaskRunEntity savedEventWaitTask(String topic, WorkflowRunEntity wfRun, RunStatus status) {
    TaskRunEntity task =
        savedTaskRun(
            "wait-for-" + topic + "-" + status,
            TaskType.eventwait,
            status,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    task.setParams(List.of(new RunParam("topic", topic, ParamType.string)));
    return taskRunRepository.save(task);
  }

  /**
   * An id-less webhook redelivery (what any at-least-once transport does) converges instead of
   * duplicating: results are merged by name on every write path - {@code
   * TaskRunService.applyEventDelivery} now keys on the result name exactly as {@code end()} does
   * through {@code ResultUtil.addUniqueResults}. Two deliveries with the same key leave one
   * element. Only the inbox ledger ({@link #deliveryWithATransportIdIsDedupedByTheInboxLedger})
   * suppresses re-applying a delivery, and it needs an id.
   */
  @Test
  void webhookRedeliveryConvergesOnOneResultPerKey() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("event-redelivery-wf", RunStatus.running, RunPhase.running);
    String taskRunId = savedEventWaitTask("build-complete", wfRun, RunStatus.ready).getId();

    workflowRunService.event(wfRun.getId(), webhookDelivery("build-complete"));
    WorkflowRunEventRequest second = webhookDelivery("build-complete");
    second.setResults(List.of(new RunResult("data", "{\"build\":\"ok-again\"}")));
    workflowRunService.event(wfRun.getId(), second);

    List<RunResult> results = taskRunRepository.findById(taskRunId).orElseThrow().getResults();
    assertEquals(
        1,
        results.stream().filter(r -> "data".equals(r.getName())).count(),
        "a redelivered key updates the element rather than appending; got " + results);
    assertEquals("{\"build\":\"ok-again\"}", results.get(0).getValue(), "last write wins");
  }

  /** A delivery with an id is applied once; a second with the same id is suppressed by the ledger. */
  @Test
  void deliveryWithATransportIdIsDedupedByTheInboxLedger() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("event-inbox-wf", RunStatus.running, RunPhase.running);
    String taskRunId = savedEventWaitTask("deploy-complete", wfRun, RunStatus.ready).getId();

    WorkflowRunEventRequest first = webhookDelivery("deploy-complete");
    first.setId("transport-event-1");
    WorkflowRunEventRequest second = webhookDelivery("deploy-complete");
    second.setId("transport-event-1");

    workflowRunService.event(wfRun.getId(), first);
    workflowRunService.event(wfRun.getId(), second);

    List<RunResult> results = taskRunRepository.findById(taskRunId).orElseThrow().getResults();
    assertEquals(
        1,
        results.stream().filter(r -> "data".equals(r.getName())).count(),
        "the inbox ledger should have suppressed the redelivery; got " + results);
  }

  /**
   * The wait-for-event callback passes its {@code eventId} to the ledger: the same callback sent
   * twice applies the first payload only.
   */
  @Test
  void aCallbackRedeliveredWithItsEventIdIsAppliedOnce() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("callback-event-id-wf", RunStatus.running, RunPhase.running);
    String taskRunId = savedEventWaitTask("release-approved", wfRun, RunStatus.ready).getId();
    ObjectNode first = JsonNodeFactory.instance.objectNode().put("build", "first");
    ObjectNode again = JsonNodeFactory.instance.objectNode().put("build", "again");

    webhookEventService.processWFE(
        wfRun.getId(), "release-approved", "succeeded", Optional.of(first), Optional.of("delivery-1"));
    webhookEventService.processWFE(
        wfRun.getId(), "release-approved", "succeeded", Optional.of(again), Optional.of("delivery-1"));

    List<RunResult> data =
        taskRunRepository.findById(taskRunId).orElseThrow().getResults().stream()
            .filter(r -> "data".equals(r.getName()))
            .toList();
    assertEquals(1, data.size(), "one data result; got " + data);
    String value = String.valueOf(data.get(0).getValue());
    assertTrue(
        value.contains("first") && !value.contains("again"), "the redelivery was applied: " + value);
  }
}
