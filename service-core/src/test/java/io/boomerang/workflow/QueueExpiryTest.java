package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.LogCapture;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowWorkspace;
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
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    expireAtTwentyFourHours(parked.getId());

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

  // The slot frees and the run is admitted between the sweep's read and its write. Its workflow
  // declares a workspace, so it waits ready and pending for a dispatcher: a pending run, created
  // before the cutoff, that only the status tells apart from a parked one.
  @Test
  void aRunAdmittedSinceItWasReadIsNotExpired() {
    String workspace = workspace("queue-expiry-admitted", true);
    holder = submit(workspace, "queue-expiry-admitted-workflow", true).getId();
    WorkflowRun parked = submit(workspace, "queue-expiry-admitted-workflow", true);
    assertEquals(RunStatus.waiting, parked.getStatus());
    backdate(parked.getId(), 30 * HOUR);
    workflowRunService.cancel(holder);
    holder = parked.getId();
    WorkflowRunEntity admitted = run(parked.getId());
    assertEquals(RunStatus.ready, admitted.getStatus(), "admitted when the slot freed");
    assertEquals(RunPhase.pending, admitted.getPhase());

    expireAtTwentyFourHours(parked.getId());

    WorkflowRunEntity after = run(parked.getId());
    assertEquals(RunStatus.ready, after.getStatus());
    assertEquals(RunPhase.pending, after.getPhase());
  }

  // The sweep re-admits before it expires: a run past the age limit whose slot is free, its
  // completion's drain lost with the instance that owed it, runs instead of being cancelled.
  @Test
  void aRunPastTheAgeLimitWhoseSlotIsFreeIsAdmittedNotExpired() {
    WorkflowRun parked = parkedRun("queue-expiry-free-slot");
    backdate(parked.getId(), 25 * HOUR);
    // The holder ends with no transition published, so nothing drains the queue.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(holder)),
        new Update().set("status", RunStatus.cancelled).set("phase", RunPhase.completed),
        WorkflowRunEntity.class);
    holder = parked.getId();

    workflowWatcher.sweepQueuedRuns();

    awaitEngine("the parked run to start")
        .untilAsserted(() -> assertEquals(RunStatus.running, run(parked.getId()).getStatus()));
    assertEquals(null, run(parked.getId()).getStatusReason());
  }

  @Test
  void aStagedRunHoldingASlotIsNeverExpired() {
    String workspace = workspace("queue-expiry-staged");
    WorkflowRun staged = submit(workspace, "queue-expiry-staged-workflow", false);
    holder = staged.getId();
    backdate(staged.getId(), 30 * HOUR);

    expireAtTwentyFourHours(staged.getId());

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

  // A value that is not a whole number of hours of 0 or more reads as 24, never as something else.
  @ParameterizedTest
  @ValueSource(strings = {"1.5", "", "   ", "abc", "-1", "24h"})
  void anUnreadableAgeLimitMeansTwentyFourHours(String value) {
    WorkflowRun young = parkedRun("queue-expiry-unreadable-" + Math.abs(value.hashCode()));
    backdate(young.getId(), 2 * HOUR);
    String previous = setSettingValue("workflowrun", "queue.max.age", value);
    try {
      workflowWatcher.expireQueuedRuns();
      assertEquals(RunStatus.waiting, run(young.getId()).getStatus(), "2 h is within 24");

      backdate(young.getId(), 25 * HOUR);
      workflowWatcher.expireQueuedRuns();

      WorkflowRunEntity expired = run(young.getId());
      assertEquals(StatusReason.QUEUE_EXPIRED, expired.getStatusReason());
      assertEquals(
          "Cancelled after waiting 25 h 0 min for a slot. The limit is 24 hours.",
          expired.getStatusMessage());
    } finally {
      setSettingValue("workflowrun", "queue.max.age", previous);
    }
  }

  @Test
  void anAgeLimitInWholeHoursIsReadWithSpacesAroundIt() {
    WorkflowRun parked = parkedRun("queue-expiry-two-hours");
    backdate(parked.getId(), 3 * HOUR);
    String previous = setSettingValue("workflowrun", "queue.max.age", " 2 ");
    try {
      workflowWatcher.expireQueuedRuns();

      assertEquals(
          "Cancelled after waiting 3 h 0 min for a slot. The limit is 2 hours.",
          run(parked.getId()).getStatusMessage());
    } finally {
      setSettingValue("workflowrun", "queue.max.age", previous);
    }
  }

  // A number of hours too large to turn into milliseconds is held to a century: it expires
  // nothing, where an overflow would put the cutoff in the future and expire every parked run.
  @ParameterizedTest
  @ValueSource(strings = {"9999999999999", "99999999999999999999999999", "2562047788016"})
  void aHugeAgeLimitExpiresNothing(String value) {
    WorkflowRun parked = parkedRun("queue-expiry-huge-" + Math.abs(value.hashCode()));
    backdate(parked.getId(), 2 * HOUR);
    String previous = setSettingValue("workflowrun", "queue.max.age", value);
    try {
      workflowWatcher.expireQueuedRuns();

      assertEquals(RunStatus.waiting, run(parked.getId()).getStatus());
    } finally {
      setSettingValue("workflowrun", "queue.max.age", previous);
      workflowRunService.cancel(parked.getId());
    }
  }

  @Test
  void anUnreadableAgeLimitIsLoggedOnceForEachValue() {
    String previous = setSettingValue("workflowrun", "queue.max.age", "soon");
    try (LogCapture logs = LogCapture.start(Level.WARN, "io.boomerang.engine.WorkflowWatcher")) {
      workflowWatcher.expireQueuedRuns();
      workflowWatcher.expireQueuedRuns();
      workflowWatcher.expireQueuedRuns();
      setSettingValue("workflowrun", "queue.max.age", "later");
      workflowWatcher.expireQueuedRuns();
      workflowWatcher.expireQueuedRuns();
      // Set right, then wrong again the same way: said again.
      setSettingValue("workflowrun", "queue.max.age", "12");
      workflowWatcher.expireQueuedRuns();
      setSettingValue("workflowrun", "queue.max.age", "later");
      workflowWatcher.expireQueuedRuns();

      assertEquals(
          List.of("'soon'", "'later'", "'later'"),
          logs.lines().stream()
              .filter(line -> line.startsWith("The workflowrun setting queue.max.age is "))
              .map(line -> line.split(" ")[5].replace(",", ""))
              .toList());
    } finally {
      setSettingValue("workflowrun", "queue.max.age", previous);
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

  private void expireAtTwentyFourHours(String id) {
    workflowExecutionService.expire(id, new Date(System.currentTimeMillis() - 24 * HOUR), 24);
  }

  private String workspace(String name) {
    return workspace(name, false);
  }

  private String workspace(String name, boolean withVolume) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(1);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    String workspace = workspaceService.create(request).getName();
    Workflow workflow = runnableWorkflow(name + "-workflow", TASK_SLUG);
    if (withVolume) {
      WorkflowWorkspace volume = new WorkflowWorkspace();
      volume.setSize("1Gi");
      workflow.setWorkspace(volume);
    }
    workflowService.create(workspace, workflow);
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
