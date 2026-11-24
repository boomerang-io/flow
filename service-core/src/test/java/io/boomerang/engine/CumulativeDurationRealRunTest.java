package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RetryScope;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.enums.ExecutionCondition;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRunRetryRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.common.model.WorkflowTaskRetry;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;

/**
 * The cumulative duration a run's completion records, for real runs driven through the engine and
 * the dispatcher protocol, against the wall clock around each task's start and end: parallel tasks
 * each count, a for-each counts its items, a requeued attempt that never ran counts nothing, a
 * node's own retry counts each attempt that ran, a cancel counts a running task up to the cancel,
 * finally tasks count, and a scoped retry counts only the tasks it re-ran.
 */
class CumulativeDurationRealRunTest extends AbstractEngineIntegrationTest {

  // The engine stamps a task's start and end with its own clock, a few milliseconds from the
  // test's readings around the calls: a figure may fall that far below the time measured between them.
  private static final long CLOCK_SLACK = 50L;

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private MongoTemplate mongo;
  @Autowired private io.boomerang.workflow.TaskService tasks;

  @BeforeEach
  void audit() {
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      settingsRepository.save(settings);
    }
  }

  @AfterEach
  void off() {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig c = new SettingConfig();
    c.setKey(key);
    c.setValue(value);
    c.setType("text");
    return c;
  }

  private String template(String name) {
    Task task = new Task();
    task.setName(name + "-" + System.nanoTime());
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    return tasks.create(task).getId();
  }

  private static WorkflowTask node(String name, TaskType type, String ref, String... deps) {
    WorkflowTask t = new WorkflowTask();
    t.setName(name);
    t.setType(type);
    t.setTaskRef(ref);
    List<WorkflowTaskDependency> d = new LinkedList<>();
    for (String dep : deps) {
      WorkflowTaskDependency x = new WorkflowTaskDependency();
      x.setTaskRef(dep);
      d.add(x);
    }
    t.setDependencies(d);
    return t;
  }

  private String runOf(List<WorkflowTask> nodes) {
    Workflow w = new Workflow();
    w.setName("cumulative-real-run-" + System.nanoTime());
    w.setTasks(new ArrayList<>(nodes));
    String id = workflowService.create(w, false).getBody().getId();
    String runId = workflowService.submit(id, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(runId, Optional.empty());
    return runId;
  }

  private TaskRunEntity awaitReady(String runId, String name) {
    awaitEngine(name + " ready")
        .until(
            () ->
                taskRunRepository
                    .findFirstByNameAndWorkflowRunRef(name, runId)
                    .map(t -> RunStatus.ready.equals(t.getStatus()))
                    .orElse(false));
    return taskRunRepository.findFirstByNameAndWorkflowRunRef(name, runId).orElseThrow();
  }

  private static TaskRunEndRequest end(RunStatus status, String reason, String dispatcher) {
    TaskRunEndRequest r = new TaskRunEndRequest();
    r.setStatus(status);
    r.setStatusReason(reason);
    r.setDispatcherRef(dispatcher);
    return r;
  }

  private Optional<TaskRunStartRequest> startBy(String dispatcher) {
    if (dispatcher == null) {
      return Optional.empty();
    }
    TaskRunStartRequest r = new TaskRunStartRequest();
    r.setDispatcherRef(dispatcher);
    return Optional.of(r);
  }

  private long[] timed(String taskRunId, long sleep, RunStatus status, String reason, String dispatcher)
      throws Exception {
    long before = System.currentTimeMillis();
    taskRunService.start(taskRunId, startBy(dispatcher));
    long afterStart = System.currentTimeMillis();
    Thread.sleep(sleep);
    long beforeEnd = System.currentTimeMillis();
    taskRunService.end(taskRunId, Optional.of(end(status, reason, dispatcher)));
    long after = System.currentTimeMillis();
    return new long[] {beforeEnd - afterStart, after - before};
  }

  private List<AuditEventEntity> events(String runId, String action) {
    return auditEventRepository.findAll().stream()
        .filter(e -> runId.equals(e.getResourceId()) && action.equals(e.getAction()))
        .toList();
  }

  private long recorded(String runId) {
    awaitEngine("run completed")
        .until(
            () ->
                RunPhase.completed.equals(
                    workflowRunRepository.findById(runId).orElseThrow().getPhase()));
    awaitEngine("UPDATE event").until(() -> !events(runId, "UPDATE").isEmpty());
    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    List<AuditEventEntity> updates = events(runId, "UPDATE");
    assertThat(updates).as("one UPDATE per run").hasSize(1);
    assertThat(events(runId, "CREATE")).as("one CREATE per run").hasSize(1);
    Object v = updates.get(0).getPayload().get("cumulativeDuration");
    for (TaskRunEntity t : taskRunRepository.findByWorkflowRunRef(runId)) {
    }
    return ((Number) v).longValue();
  }

  private String registered(String name, String types) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name + System.nanoTime(), name + ".local", List.of(types)));
  }

  private boolean claimed(String dispatcher, String taskSlug, String id) {
    ResponseEntity<List<TaskRun>> r = dispatcherService.getTaskQueue(dispatcher, 5, null, null, null);
    return r != null && r.getBody() != null && r.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  private void clearBackoff(String id) {
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("retry.after", new Date(System.currentTimeMillis() - 60_000)),
        TaskRunEntity.class);
  }

  @Test
  void parallelTasksEachCount() throws Exception {
    String t = template("cumulative-real-run-par");
    String runId =
        runOf(
            List.of(
                node("start", TaskType.start, null),
                node("a", TaskType.template, t, "start"),
                node("b", TaskType.template, t, "start"),
                node("end", TaskType.end, null, "a", "b")));
    String a = awaitReady(runId, "a").getId();
    String b = awaitReady(runId, "b").getId();
    taskRunService.start(a, Optional.empty());
    taskRunService.start(b, Optional.empty());
    Thread.sleep(400);
    taskRunService.end(a, Optional.of(end(RunStatus.succeeded, null, null)));
    Thread.sleep(300);
    taskRunService.end(b, Optional.of(end(RunStatus.succeeded, null, null)));
    long rec = recorded(runId);
    long expected = taskRunRepository.findById(a).get().getDuration()
        + taskRunRepository.findById(b).get().getDuration();
    assertThat(rec).isEqualTo(expected);
    assertThat(rec).isBetween(1100L, 3000L);
  }

  @Test
  void aForEachCountsItsItems() throws Exception {
    String t = template("cumulative-real-run-fe");
    WorkflowTask fe = node("fe", TaskType.template, t, "start");
    WorkflowTaskForeach foreach = new WorkflowTaskForeach();
    foreach.setItems(List.of("1", "2", "3", "4", "5"));
    fe.setForeach(foreach);
    String runId =
        runOf(List.of(node("start", TaskType.start, null), fe, node("end", TaskType.end, null, "fe")));
    awaitEngine("items")
        .until(
            () ->
                taskRunRepository.findByWorkflowRunRef(runId).stream()
                        .filter(x -> x.getParentRef() != null && RunStatus.ready.equals(x.getStatus()))
                        .count()
                    == 5);
    long sumWall = 0;
    for (TaskRunEntity item :
        taskRunRepository.findByWorkflowRunRef(runId).stream()
            .filter(x -> x.getParentRef() != null)
            .toList()) {
      sumWall += timed(item.getId(), 200, RunStatus.succeeded, null, null)[0];
    }
    long rec = recorded(runId);
    long items =
        taskRunRepository.findByWorkflowRunRef(runId).stream()
            .filter(x -> x.getParentRef() != null)
            .mapToLong(TaskRunEntity::getDuration)
            .sum();
    assertThat(rec).isEqualTo(items);
  }

  // Attempt 1 starts, runs 600ms, the dispatcher reports StartTimeout; attempt 2 is claimed, sits
  // claimed 400ms, starts, runs 300ms, succeeds. Only ~300ms should count.
  @Test
  void anAttemptThatNeverRanCountsNothingAndTheNextCounts() throws Exception {
    String t = template("cumulative-real-run-rq");
    String runId =
        runOf(List.of(node("start", TaskType.start, null), node("a", TaskType.template, t, "start"),
            node("end", TaskType.end, null, "a")));
    String a = awaitReady(runId, "a").getId();
    String d = registered("cumulative-real-run-rq", "template");
    awaitEngine("claim 1").until(() -> claimed(d, t, a));
    timed(a, 600, RunStatus.failed, "StartTimeout", d);
    // termination order releases the claim
    awaitEngine("released")
        .until(
            () -> {
              dispatcherService.getTaskQueue(d, 0, null, null, null);
              TaskRunEntity x = taskRunRepository.findById(a).get();
              return x.getClaim() == null || x.getClaim().getBy() == null;
            });
    clearBackoff(a);
    awaitEngine("claim 2").until(() -> claimed(d, t, a));
    Thread.sleep(400);
    long[] w = timed(a, 300, RunStatus.succeeded, null, d);
    long rec = recorded(runId);
    assertThat(rec).isBetween(w[0] - CLOCK_SLACK, w[1]);
  }

  // Attempt 1 starts and runs 600ms then StartTimeout; attempt 2 claimed, never started; cancel.
  @Test
  void aRequeuedTaskCancelledWhileClaimedCountsNothing() throws Exception {
    String t = template("cumulative-real-run-rqc");
    String runId =
        runOf(List.of(node("start", TaskType.start, null), node("a", TaskType.template, t, "start"),
            node("end", TaskType.end, null, "a")));
    String a = awaitReady(runId, "a").getId();
    String d = registered("cumulative-real-run-rqc", "template");
    awaitEngine("claim 1").until(() -> claimed(d, t, a));
    timed(a, 600, RunStatus.failed, "StartTimeout", d);
    awaitEngine("released")
        .until(
            () -> {
              dispatcherService.getTaskQueue(d, 0, null, null, null);
              TaskRunEntity x = taskRunRepository.findById(a).get();
              return x.getClaim() == null || x.getClaim().getBy() == null;
            });
    clearBackoff(a);
    awaitEngine("claim 2").until(() -> claimed(d, t, a));
    Thread.sleep(500);
    workflowRunService.cancel(runId);
    long rec = recorded(runId);
    assertThat(rec).isEqualTo(0L);
  }

  // retry.max=1: attempt 1 runs 400ms and fails; attempt 2 runs 300ms and succeeds: ~700ms.
  @Test
  void aNodeRetryCountsEachAttemptThatRan() throws Exception {
    String t = template("cumulative-real-run-nr");
    WorkflowTask a = node("a", TaskType.template, t, "start");
    WorkflowTaskRetry retry = new WorkflowTaskRetry();
    retry.setMax(1);
    a.setRetry(retry);
    String runId =
        runOf(List.of(node("start", TaskType.start, null), a, node("end", TaskType.end, null, "a")));
    String id = awaitReady(runId, "a").getId();
    String d = registered("cumulative-real-run-nr", "template");
    awaitEngine("claim 1").until(() -> claimed(d, t, id));
    long[] w1 = timed(id, 400, RunStatus.failed, null, d);
    awaitEngine("released")
        .until(
            () -> {
              dispatcherService.getTaskQueue(d, 0, null, null, null);
              TaskRunEntity x = taskRunRepository.findById(id).get();
              return RunPhase.pending.equals(x.getPhase())
                  && (x.getClaim() == null || x.getClaim().getBy() == null);
            });
    clearBackoff(id);
    awaitEngine("claim 2").until(() -> claimed(d, t, id));
    long[] w2 = timed(id, 300, RunStatus.succeeded, null, d);
    long rec = recorded(runId);
    assertThat(rec).isBetween(w1[0] + w2[0] - 2 * CLOCK_SLACK, w1[1] + w2[1]);
  }

  // retry.max=1: attempt 1 runs 400ms and fails; run cancelled during the backoff: ~400ms.
  @Test
  void aNodeRetryCancelledInItsBackoffCountsTheAttemptThatRan() throws Exception {
    String t = template("cumulative-real-run-nrc");
    WorkflowTask a = node("a", TaskType.template, t, "start");
    WorkflowTaskRetry retry = new WorkflowTaskRetry();
    retry.setMax(1);
    a.setRetry(retry);
    String runId =
        runOf(List.of(node("start", TaskType.start, null), a, node("end", TaskType.end, null, "a")));
    String id = awaitReady(runId, "a").getId();
    String d = registered("cumulative-real-run-nrc", "template");
    awaitEngine("claim 1").until(() -> claimed(d, t, id));
    long[] w1 = timed(id, 400, RunStatus.failed, null, d);
    Thread.sleep(700);
    workflowRunService.cancel(runId);
    long rec = recorded(runId);
    assertThat(rec).isBetween(w1[0] - CLOCK_SLACK, w1[1]);
  }

  // A task running 500ms, then the run is cancelled; ending the task later adds nothing.
  @Test
  void aCancelCountsARunningTaskUpToTheCancel() throws Exception {
    String t = template("cumulative-real-run-cx");
    String runId =
        runOf(List.of(node("start", TaskType.start, null), node("a", TaskType.template, t, "start"),
            node("end", TaskType.end, null, "a")));
    String a = awaitReady(runId, "a").getId();
    long before = System.currentTimeMillis();
    taskRunService.start(a, Optional.empty());
    long afterStart = System.currentTimeMillis();
    Thread.sleep(500);
    long beforeCancel = System.currentTimeMillis();
    workflowRunService.cancel(runId);
    long afterCancel = System.currentTimeMillis();
    long rec = recorded(runId);
    Thread.sleep(500);
    try {
      taskRunService.end(a, Optional.of(end(RunStatus.succeeded, null, null)));
    } catch (RuntimeException ignored) {
      // A late end may be refused: the task completed with its run.
    }
    Thread.sleep(1000);
    assertThat(rec).isBetween(beforeCancel - afterStart - CLOCK_SLACK, afterCancel - before);
    assertThat(events(runId, "UPDATE")).hasSize(1);
  }

  // A finally template task: work 300ms, then finally 400ms: ~700ms.
  @Test
  void aFinallyTaskCounts() throws Exception {
    String t = template("cumulative-real-run-fin");
    WorkflowTask fin = node("fin", TaskType.template, t);
    WorkflowTaskDependency dep = new WorkflowTaskDependency();
    dep.setTaskRef("end");
    dep.setExecutionCondition(ExecutionCondition.Finally);
    fin.setDependencies(new LinkedList<>(List.of(dep)));
    String runId =
        runOf(
            List.of(
                node("start", TaskType.start, null),
                node("a", TaskType.template, t, "start"),
                node("end", TaskType.end, null, "a"),
                fin));
    long[] w1 = timed(awaitReady(runId, "a").getId(), 300, RunStatus.succeeded, null, null);
    long[] w2 = timed(awaitReady(runId, "fin").getId(), 400, RunStatus.succeeded, null, null);
    long rec = recorded(runId);
    assertThat(rec).isBetween(w1[0] + w2[0] - 2 * CLOCK_SLACK, w1[1] + w2[1]);
  }

  // a succeeds (300ms), b fails (200ms); a scoped retry (failed) re-runs b only (250ms).
  @Test
  void aScopedRetryCountsOnlyTheTasksItReRan() throws Exception {
    String t = template("cumulative-real-run-sr");
    String runId =
        runOf(
            List.of(
                node("start", TaskType.start, null),
                node("a", TaskType.template, t, "start"),
                node("b", TaskType.template, t, "a"),
                node("end", TaskType.end, null, "b")));
    timed(awaitReady(runId, "a").getId(), 300, RunStatus.succeeded, null, null);
    timed(awaitReady(runId, "b").getId(), 200, RunStatus.failed, null, null);
    long first = recorded(runId);
    WorkflowRunRetryRequest request = new WorkflowRunRetryRequest();
    request.setScope(RetryScope.failed);
    String retryId = workflowRunService.retry(runId, true, 1, request).getId();
    long[] w = timed(awaitReady(retryId, "b").getId(), 250, RunStatus.succeeded, null, null);
    long rec = recorded(retryId);
    assertThat(rec).isBetween(w[0] - CLOCK_SLACK, w[1]);
  }
}
