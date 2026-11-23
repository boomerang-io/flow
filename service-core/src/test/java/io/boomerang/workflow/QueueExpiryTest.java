package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.WorkflowExecutionService;
import io.boomerang.engine.WorkflowWatcher;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * A run that has waited for a slot longer than the queue's age limit ends cancelled with the
 * reason {@code QueueExpired} and its wait in the message. Only a run still parked is expired: one
 * admitted since the sweep read it runs on. A limit of 0 expires nothing.
 */
@RecordApplicationEvents
class QueueExpiryTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";
  private static final String TASK_SLUG = "queue-expiry-test-task";
  private static final long HOUR = 3600000L;

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowWatcher workflowWatcher;
  @Autowired private WorkflowExecutionService workflowExecutionService;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ApplicationEvents events;

  private SettingEntity auditSettingsAdded;
  private String holder;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    cancelRunsEarlierTestsLeftParked();
    setFeatureSetting(QUOTA_FEATURE, true);
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      auditSettingsAdded = settingsRepository.save(settings);
    }
  }

  @AfterEach
  void restoreSharedState() {
    setFeatureSetting(QUOTA_FEATURE, false);
    if (auditSettingsAdded != null) {
      settingsRepository.delete(auditSettingsAdded);
      auditSettingsAdded = null;
    }
    if (holder != null) {
      workflowRunService.cancel(holder);
      holder = null;
    }
  }

  @Test
  void aRunParkedPastTheAgeLimitEndsCancelledWithTheReasonAndItsWait() {
    WorkflowRun parked = parkedRun("queue-expiry-old");
    backdate(parked.getId(), 25 * HOUR + 3 * 60000L);

    workflowWatcher.expireQueuedRuns();

    WorkflowRunEntity expired = run(parked.getId());
    assertEquals(RunStatus.cancelled, expired.getStatus());
    assertEquals(RunPhase.completed, expired.getPhase());
    assertEquals(StatusReason.QUEUE_EXPIRED, expired.getStatusReason());
    assertEquals(
        "Cancelled after waiting 25 h 3 min for a slot. The limit is 24 hours.",
        expired.getStatusMessage());
    assertEquals(0, expired.getDuration());
    awaitEngine("the task runs admission created to be wound down")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository.findByWorkflowRunRef(parked.getId()).stream()
                        .allMatch(task -> RunPhase.completed.equals(task.getPhase()))));
  }

  @Test
  void expiryPublishesOneCompletionAndWritesOneAuditUpdate() {
    WorkflowRun parked = parkedRun("queue-expiry-events");
    backdate(parked.getId(), 30 * HOUR);
    events.clear();

    workflowWatcher.expireQueuedRuns();
    // A second sweep, and a second instance's, find nothing left to expire.
    workflowWatcher.expireQueuedRuns();
    workflowExecutionService.expire(parked.getId(), 24);

    List<WorkflowRunTransition> completions =
        events.stream(WorkflowRunTransition.class)
            .filter(t -> parked.getId().equals(t.id()))
            .toList();
    assertEquals(1, completions.size(), "one status event for the expiry");
    assertEquals(RunStatus.waiting, completions.get(0).fromStatus());
    assertEquals(RunStatus.cancelled, completions.get(0).toStatus());
    assertEquals(RunPhase.completed, completions.get(0).toPhase());
    awaitEngine("the expiry's audit event")
        .untilAsserted(() -> assertEquals(1, auditUpdates(parked.getId())));
    assertEquals(1, auditUpdates(parked.getId()));
  }

  @Test
  void aRunAdmittedSinceItWasReadIsNotExpired() {
    WorkflowRun parked = parkedRun("queue-expiry-admitted");
    backdate(parked.getId(), 30 * HOUR);
    // The slot frees and the run is admitted between the sweep's read and its write.
    workflowRunService.cancel(holder);
    awaitEngine("the parked run to be admitted")
        .untilAsserted(() -> assertEquals(RunStatus.running, run(parked.getId()).getStatus()));
    holder = parked.getId();

    workflowExecutionService.expire(parked.getId(), 24);

    assertEquals(RunStatus.running, run(parked.getId()).getStatus());
  }

  @Test
  void aStagedRunHoldingASlotIsNeverExpired() {
    String workspace = workspace("queue-expiry-staged");
    WorkflowRun staged = submit(workspace, "queue-expiry-staged-workflow", false);
    holder = staged.getId();
    backdate(staged.getId(), 30 * HOUR);

    workflowExecutionService.expire(staged.getId(), 24);

    WorkflowRunEntity after = run(staged.getId());
    assertEquals(RunStatus.ready, after.getStatus(), "ready and pending is admitted, not parked");
    assertEquals(RunPhase.pending, after.getPhase());
  }

  @Test
  void anAgeLimitOfZeroExpiresNothing() {
    WorkflowRun parked = parkedRun("queue-expiry-off");
    backdate(parked.getId(), 300 * HOUR);
    String previous = setSettingValue("workflowrun", "queue.max.age", "0");
    try {
      workflowWatcher.expireQueuedRuns();

      assertEquals(RunStatus.waiting, run(parked.getId()).getStatus());
    } finally {
      setSettingValue("workflowrun", "queue.max.age", previous);
      workflowRunService.cancel(parked.getId());
    }
  }

  @Test
  void aRunParkedWithinTheLimitWaitsOn() {
    WorkflowRun parked = parkedRun("queue-expiry-young");
    backdate(parked.getId(), 23 * HOUR);

    workflowWatcher.expireQueuedRuns();

    assertEquals(RunStatus.waiting, run(parked.getId()).getStatus());
    workflowRunService.cancel(parked.getId());
  }

  // A workspace of one slot, the slot taken, and a second run parked behind it.
  private WorkflowRun parkedRun(String name) {
    String workspace = workspace(name);
    holder = submit(workspace, name + "-workflow", true).getId();
    WorkflowRun parked = submit(workspace, name + "-workflow", true);
    assertEquals(RunStatus.waiting, parked.getStatus());
    return parked;
  }

  private String workspace(String name) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(1);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    String workspace = workspaceService.create(request).getName();
    workflowService.create(workspace, runnableWorkflow(name + "-workflow", TASK_SLUG));
    return workspace;
  }

  private WorkflowRun submit(String workspace, String workflow, boolean start) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return workflowService.submit(workspace, workflow, request, start);
  }

  private void backdate(String id, long millis) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("creationDate", new Date(System.currentTimeMillis() - millis)),
        WorkflowRunEntity.class);
  }

  private WorkflowRunEntity run(String id) {
    return workflowRunRepository.findById(id).orElseThrow();
  }

  private long auditUpdates(String runId) {
    return auditEventRepository.findAll().stream()
        .filter(event -> runId.equals(event.getResourceId()))
        .filter(event -> "UPDATE".equals(event.getAction()))
        .count();
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}
