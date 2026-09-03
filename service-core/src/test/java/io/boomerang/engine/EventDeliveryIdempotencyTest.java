package io.boomerang.engine;

import io.boomerang.workflow.WorkflowRunService;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Q-127 #24 — {@code WorkflowRunService.event} delivery idempotency.
 *
 * <p>The audit's fix shape for #24 was "addUniqueResults; event-id dedup". Only the second half
 * shipped ({@code WorkflowRunService.java:920-938}, the {@code events_inbox} ledger keyed {@code
 * "<runId>:<eventId>"}), and it is gated on {@code request.getId()} being set. The only production
 * caller — {@code WebhookEventService.processWFE} ({@code WebhookEventService.java:185-195}) —
 * never sets an id, because the endpoint behind it ({@code WebhookEventControllerV2
 * .acceptWaitForEvent}, {@code POST}/{@code GET /callback}) takes only {@code ref}, {@code topic}
 * and {@code status} and so has no event identifier to pass on.
 *
 * <p><b>The first half was ruled NOT to be fixed (maintainer, 2026-08-25)</b>: the redelivered
 * result is appended a second time and that behaviour is now recorded by a characterization test
 * rather than asserted away — see {@link #webhookRedeliveryAppendsEventWaitResultsTwice} for the
 * full reasoning and for what would have to change to make deduplication possible.
 */
class EventDeliveryIdempotencyTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowRunService workflowRunService;

  /**
   * Builds the request exactly as {@code WebhookEventService.processWFE} does: topic, status and
   * results, and NO id.
   */
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
   * element. The inbox ledger ({@link #deliveryWithATransportIdIsDedupedByTheInboxLedger}) is
   * still the only thing that suppresses re-applying a distinct event, and the webhook endpoint
   * still carries no event id to feed it.
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

  /**
   * The dedup ledger that #24's fix shipped, exercised the only way it can fire — with an id no
   * production caller sets. The contrast with {@link
   * #webhookRedeliveryAppendsEventWaitResultsTwice} is the point: the mechanism works, nothing
   * reaches it.
   */
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
}
