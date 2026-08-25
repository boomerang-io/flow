package io.boomerang.engine;

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
   * CHARACTERIZATION — asserts the CURRENT (duplicating) behaviour so it is recorded in the test
   * suite rather than only in a review note. This test previously asserted the fix; the maintainer
   * ruled (2026-08-25) NOT to fix the duplication, and the test was converted rather than deleted.
   *
   * <p>Why the ruling:
   *
   * <ul>
   *   <li>The duplicate is never <em>read</em>. {@code ParameterManager:319-322} resolves a result
   *       with {@code .findFirst()}, so a second entry with the same name and value is inert — it
   *       is visible only in the API/UI results list.
   *   <li>It is inherited, not introduced. v4 {@code main} has the identical {@code addAll} +
   *       {@code save} ({@code service-engine/.../engine/WorkflowRunService.java:567-570}), and it
   *       was never reported in v3 or v4.
   *   <li>The dedup mechanism is sound but unreachable. The {@code events_inbox} ledger in {@code
   *       WorkflowRunService.event} ({@code :920-938}) works — proven by {@link
   *       #deliveryWithATransportIdIsDedupedByTheInboxLedger} — but the webhook endpoint {@code
   *       WebhookEventControllerV2.acceptWaitForEvent} accepts no event identifier to key it on.
   *   <li>The only fix without an identifier is a payload hash, which would collapse two
   *       legitimately identical events — a behaviour change to fix something never observed.
   * </ul>
   *
   * <p><b>To make deduplication possible, the webhook endpoint must carry an event identifier</b>
   * (a transport header or body field) through {@code WebhookEventService.processWFE} into {@code
   * WorkflowRunEventRequest.id}; the ledger then handles the rest and this assertion inverts to 1.
   *
   * <p>Note the F1 fix (event delivery is now three field-scoped operators rather than a
   * whole-document save) deliberately preserved this: results are appended with {@code
   * $push}/{@code $each}, not {@code $addToSet}.
   */
  @Test
  void webhookRedeliveryAppendsEventWaitResultsTwice() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("event-redelivery-wf", RunStatus.running, RunPhase.running);
    String taskRunId = savedEventWaitTask("build-complete", wfRun, RunStatus.ready).getId();

    // Two deliveries of the same webhook event - what any at-least-once webhook transport does.
    workflowRunService.event(wfRun.getId(), webhookDelivery("build-complete"));
    workflowRunService.event(wfRun.getId(), webhookDelivery("build-complete"));

    List<RunResult> results = taskRunRepository.findById(taskRunId).orElseThrow().getResults();
    assertEquals(
        2,
        results.stream().filter(r -> "data".equals(r.getName())).count(),
        "KNOWN, RULED-ACCEPTED BEHAVIOUR: an id-less webhook redelivery appends its results again."
            + " Invert this to 1 once the webhook endpoint carries an event identifier the inbox"
            + " ledger can key on; got "
            + results);
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
