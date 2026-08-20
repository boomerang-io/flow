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
 * shipped ({@code WorkflowRunService.java:915-931}, the {@code events_inbox} ledger keyed
 * {@code "<runId>:<eventId>"}), and it is gated on {@code request.getId()} being set. The only
 * production caller — {@code WebhookEventService.processWFE}
 * ({@code WebhookEventService.java:185-195}) — never sets an id, so every real delivery takes the
 * un-deduped path and the non-waiting branch's {@code tr.getResults().addAll(...)}
 * ({@code WorkflowRunService.java:952}) appends again on every redelivery.
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

  @Test
  void webhookRedeliveryMustNotDuplicateEventWaitResults() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("event-redelivery-wf", RunStatus.running, RunPhase.running);
    String taskRunId = savedEventWaitTask("build-complete", wfRun, RunStatus.ready).getId();

    // Two deliveries of the same webhook event - what any at-least-once webhook transport does.
    workflowRunService.event(wfRun.getId(), webhookDelivery("build-complete"));
    workflowRunService.event(wfRun.getId(), webhookDelivery("build-complete"));

    List<RunResult> results = taskRunRepository.findById(taskRunId).orElseThrow().getResults();
    assertEquals(
        1,
        results.stream().filter(r -> "data".equals(r.getName())).count(),
        "a redelivered event must not append its results a second time; got " + results);
  }

  /**
   * The dedup ledger that #24's fix shipped, exercised the only way it can fire — with an id no
   * production caller sets. Passing here while {@link
   * #webhookRedeliveryMustNotDuplicateEventWaitResults} fails is the point: the mechanism works,
   * nothing reaches it.
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
