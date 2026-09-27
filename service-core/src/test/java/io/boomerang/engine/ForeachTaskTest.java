package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;

/**
 * A task with foreach set runs once per item: one parent TaskRun in the graph, never claimable,
 * plus one ordinary item TaskRun per element. The parent ends when every item is terminal, with
 * each declared result collected into an array in item order.
 */
class ForeachTaskTest extends AbstractEngineIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowWatcher workflowWatcher;
  @Autowired private MongoTemplate mongoTemplate;

  private String stageRef;
  private String locateRef;
  private String reportRef;

  @BeforeEach
  void seedCatalogue() {
    seedRelationshipRoot();
    seedTaskSettings();
    stageRef = template("foreach-stage-" + System.nanoTime(), null, "batches");
    locateRef = template("foreach-locate-" + System.nanoTime(), null, "found");
    reportRef = template("foreach-report-" + System.nanoTime(), "input", null);
  }

  @Test
  void eachItemRunsAndTheNextTaskReceivesTheResultsAsAnArray() {
    String runId = submit(workflow("foreach-three", "$(tasks.stage.results.batches)"));
    endTask(runId, "stage", RunStatus.succeeded, "batches", "[\"a\",\"b\",\"c\"]");

    TaskRunEntity parent = awaitParentRunning(runId);
    List<TaskRunEntity> items = awaitItemsReady(parent, 3);
    // Every item is an ordinary claimable TaskRun carrying its element and position.
    for (int index = 0; index < 3; index++) {
      TaskRunEntity item = items.get(index);
      assertEquals("locate[" + index + "]", item.getName());
      assertEquals(index, item.getIndex());
      assertEquals(List.of("a", "b", "c").get(index), paramValue(item, "item"));
      assertEquals(String.valueOf(index), paramValue(item, "index"));
      assertEquals(TaskType.template, item.getType());
      assertTrue(item.getDependencies().isEmpty());
    }
    // The parent is never claimable and carries nothing the lease, orphan or timeout sweeps read.
    parent = taskRunRepository.findById(parent.getId()).orElseThrow();
    assertEquals(RunStatus.running, parent.getStatus());
    assertNull(parent.getClaim());
    assertNull(parent.getTimeoutAt());

    for (TaskRunEntity item : items) {
      endTaskRun(item.getId(), RunStatus.succeeded, "found", "found-" + item.getIndex());
    }

    TaskRunEntity report = awaitAdmitted(runId, "report");
    assertEquals(
        "[\"found-0\",\"found-1\",\"found-2\"]",
        paramValue(report, "input"),
        "$(tasks.locate.results.found) resolves on the parent, not on an item");
    TaskRunEntity completed = taskRunRepository.findById(parent.getId()).orElseThrow();
    assertEquals(RunStatus.succeeded, completed.getStatus());
    assertEquals(RunPhase.completed, completed.getPhase());
  }

  @Test
  void aFailedItemFailsTheTaskAndAnAlwaysConnectionStillRunsTheNextTask() {
    String runId = submit(workflow("foreach-one-fails", List.of("x", "y")));
    endTask(runId, "stage", RunStatus.succeeded, null, null);
    List<TaskRunEntity> items = awaitItemsReady(awaitParentRunning(runId), 2);

    endTaskRun(items.get(0).getId(), RunStatus.succeeded, "found", "found-x");
    endTaskRun(items.get(1).getId(), RunStatus.failed, "found", "found-y");

    TaskRunEntity report = awaitAdmitted(runId, "report");
    assertEquals("[\"found-x\",null]", paramValue(report, "input"));
    TaskRunEntity parent =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("locate", runId).orElseThrow();
    assertEquals(RunStatus.failed, parent.getStatus());
    assertEquals("ItemFailed", parent.getStatusReason());
    assertEquals("1 of 2 items did not succeed.", parent.getStatusMessage());
  }

  @Test
  void anEmptyArraySucceedsTheTaskAtOnce() {
    String runId = submit(workflow("foreach-empty", List.of()));
    endTask(runId, "stage", RunStatus.succeeded, null, null);

    TaskRunEntity report = awaitAdmitted(runId, "report");
    assertEquals("[]", paramValue(report, "input"));
    TaskRunEntity parent =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("locate", runId).orElseThrow();
    assertEquals(RunStatus.succeeded, parent.getStatus());
    assertTrue(taskRunRepository.findByParentRefOrderByIndexAsc(parent.getId()).isEmpty());
  }

  @Test
  void itemsOverTheCapFailTheTaskWithAReasonAndCreateNoItem() {
    String runId = submit(workflow("foreach-over-cap", "$(tasks.stage.results.batches)"));
    String tooMany =
        MAPPER.writeValueAsString(
            IntStream.rangeClosed(0, TaskExecutionService.DEFAULT_MAX_FOREACH_ITEMS).boxed().toList());
    endTask(runId, "stage", RunStatus.succeeded, "batches", tooMany);

    TaskRunEntity parent = awaitParentCompleted(runId);
    assertEquals(RunStatus.failed, parent.getStatus());
    assertEquals("ForeachTooManyItems", parent.getStatusReason());
    assertTrue(taskRunRepository.findByParentRefOrderByIndexAsc(parent.getId()).isEmpty());
  }

  @Test
  void itemsThatAreNotAnArrayFailTheTaskWithAReason() {
    String runId = submit(workflow("foreach-not-array", "$(tasks.stage.results.batches)"));
    endTask(runId, "stage", RunStatus.succeeded, "batches", "{\"not\":\"an array\"}");

    TaskRunEntity parent = awaitParentCompleted(runId);
    assertEquals(RunStatus.failed, parent.getStatus());
    assertEquals("ForeachItemsInvalid", parent.getStatusReason());
  }

  @Test
  void cancellingTheRunMidFanOutLeavesNoItemInFlight() {
    String runId = submit(workflow("foreach-cancel", List.of("x", "y", "z")));
    endTask(runId, "stage", RunStatus.succeeded, null, null);
    TaskRunEntity parent = awaitParentRunning(runId);
    List<TaskRunEntity> items = awaitItemsReady(parent, 3);
    // One item claimed by a dispatcher, the others still waiting to be claimed.
    assertNotNull(taskRunService.tryClaim(items.get(0).getId(), "foreach-cancel-dispatcher"));

    workflowRunService.cancel(runId);

    awaitEngine("every item and the parent completed")
        .untilAsserted(
            () -> {
              assertEquals(
                  RunPhase.completed,
                  taskRunRepository.findById(parent.getId()).orElseThrow().getPhase());
              taskRunRepository
                  .findByParentRefOrderByIndexAsc(parent.getId())
                  .forEach(item -> assertEquals(RunPhase.completed, item.getPhase(), item.getName()));
            });
    assertEquals(
        RunStatus.cancelled, taskRunRepository.findById(items.get(0).getId()).orElseThrow().getStatus());
  }

  @Test
  void aPausedRunHoldsTheFanOutAndResumeRunsIt() {
    String runId = submit(workflow("foreach-pause", List.of("x", "y")));
    awaitAdmitted(runId, "stage");
    workflowRunService.pause(runId);
    endTask(runId, "stage", RunStatus.succeeded, null, null);

    awaitEngine("the paused run holds the foreach task back")
        .during(Duration.ofSeconds(2))
        .until(
            () ->
                RunStatus.notstarted.equals(
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("locate", runId)
                        .orElseThrow()
                        .getStatus()));

    workflowRunService.resume(runId);

    awaitItemsReady(awaitParentRunning(runId), 2);
  }

  /*
   * A crash between the parent's fan-out and its items: the parent is running with its resolved
   * items recorded but none created. While the run is paused the recovery leaves it alone - the
   * pause gate would refuse every item it queued, on every sweep - and resume runs the fan-out.
   */
  @Test
  void recoverySkipsAPausedRunAndResumeRunsTheFanOut() {
    WorkflowRunEntity wfRun = savedWorkflowRun("foreach-recover-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity parent = savedCrashedParent(wfRun, List.of("x", "y"));
    workflowRunService.pause(wfRun.getId());

    workflowWatcher.recoverForeachTasks();

    awaitEngine("the paused run's fan-out is not recovered")
        .during(Duration.ofSeconds(1))
        .until(() -> taskRunRepository.findByParentRefOrderByIndexAsc(parent.getId()).isEmpty());

    workflowRunService.resume(wfRun.getId());

    awaitItemsReady(parent, 2);
  }

  /*
   * A crash between the last item ending and the parent completing: every item is terminal and the
   * parent is still running. The recovery completes it with the collected results.
   */
  @Test
  void recoveryCompletesAParentWhoseItemsAreAllTerminal() {
    WorkflowRunEntity wfRun = savedWorkflowRun("foreach-finish-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity parent = savedCrashedParent(wfRun, List.of("x", "y"));
    savedItem(parent, 0, RunStatus.succeeded, RunPhase.completed, "found-x");
    savedItem(parent, 1, RunStatus.succeeded, RunPhase.completed, "found-y");

    workflowWatcher.recoverForeachTasks();

    awaitEngine("the parent completed from its terminal items")
        .untilAsserted(
            () -> {
              TaskRunEntity completed = taskRunRepository.findById(parent.getId()).orElseThrow();
              assertEquals(RunPhase.completed, completed.getPhase());
              assertEquals(RunStatus.succeeded, completed.getStatus());
              assertEquals(List.of("found-x", "found-y"), resultValue(completed, "found"));
            });
  }

  /*
   * A lost end after the outcome is recorded: the parent holds a terminal status in the running
   * phase. The recovery ends it, and a second pass is a no-op, so the task after it runs exactly
   * once - one approval record.
   */
  @Test
  void recoveryEndsAParentWhoseOutcomeIsRecordedButWasNeverEnded() {
    WorkflowRunEntity wfRun = savedWorkflowRun("foreach-lost-end-wf", RunStatus.running, RunPhase.running);
    savedTaskRun(
        "start", TaskType.start, RunStatus.succeeded, RunPhase.completed, wfRun.getWorkflowRef(), wfRun.getId());
    TaskRunEntity parent = savedCrashedParent(wfRun, List.of("x"));
    TaskRunEntity gate = savedGateAfterParent(wfRun);
    savedItem(parent, 0, RunStatus.succeeded, RunPhase.completed, "found-x");
    taskRunService.tryRecordForeachOutcome(
        parent.getId(),
        RunStatus.succeeded,
        "All 1 items succeeded.",
        null,
        List.of(new RunResult("found", List.of("found-x"))));

    workflowWatcher.recoverForeachTasks();
    workflowWatcher.recoverForeachTasks();

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(gate.getId()));
    awaitEngine("the task after the parent is admitted")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    awaitEngine("no second admission appears")
        .during(Duration.ofSeconds(2))
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    TaskRunEntity completed = taskRunRepository.findById(parent.getId()).orElseThrow();
    assertEquals(RunPhase.completed, completed.getPhase());
    assertEquals(RunStatus.succeeded, completed.getStatus());
    assertEquals(List.of("found-x"), resultValue(completed, "found"));
  }

  /*
   * Combined results past MongoDB's 16 MB document limit: seventeen items of about 1 MB each. The
   * aggregate cannot be stored, so the parent fails with ResultsTooLarge and no results and the
   * run moves on instead of retrying the same write forever.
   */
  @Test
  void combinedResultsOverTheDocumentLimitFailTheTaskAndTheRunMovesOn() {
    WorkflowRunEntity wfRun = savedWorkflowRun("foreach-too-large-wf", RunStatus.running, RunPhase.running);
    savedTaskRun(
        "start", TaskType.start, RunStatus.succeeded, RunPhase.completed, wfRun.getWorkflowRef(), wfRun.getId());
    List<Object> elements = new ArrayList<>(IntStream.range(0, 17).boxed().toList());
    TaskRunEntity parent = savedCrashedParent(wfRun, elements);
    TaskRunEntity gate = savedGateAfterParent(wfRun);
    String nearlyOneMegabyte = "x".repeat(1_000_000);
    for (int index = 0; index < elements.size(); index++) {
      savedItem(parent, index, RunStatus.succeeded, RunPhase.completed, nearlyOneMegabyte);
    }

    workflowWatcher.recoverForeachTasks();

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(gate.getId()));
    awaitEngine("the task after the parent is admitted")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    TaskRunEntity completed = taskRunRepository.findById(parent.getId()).orElseThrow();
    assertEquals(RunPhase.completed, completed.getPhase());
    assertEquals(RunStatus.failed, completed.getStatus());
    assertEquals("ResultsTooLarge", completed.getStatusReason());
    assertTrue(completed.getResults().stream().allMatch(result -> result.getValue() == null));
  }

  /*
   * Concurrent last-item ends: each end completes its own item, then both may see every item
   * terminal. The parent's completion Compare-And-Set admits one winner, so the task after it runs
   * exactly once - one approval record.
   */
  @Test
  void concurrentLastItemEndsCompleteTheParentExactlyOnce() throws Exception {
    WorkflowRunEntity wfRun = savedWorkflowRun("foreach-race-wf", RunStatus.running, RunPhase.running);
    savedTaskRun(
        "start", TaskType.start, RunStatus.succeeded, RunPhase.completed, wfRun.getWorkflowRef(), wfRun.getId());
    TaskRunEntity parent = savedCrashedParent(wfRun, List.of("x", "y"));
    TaskRunEntity gate = savedGateAfterParent(wfRun);
    TaskRunEntity first = savedItem(parent, 0, RunStatus.running, RunPhase.running, null);
    TaskRunEntity second = savedItem(parent, 1, RunStatus.running, RunPhase.running, null);

    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<Void>> ends = new ArrayList<>();
    for (TaskRunEntity item : List.of(first, second)) {
      ends.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                endTaskRun(item.getId(), RunStatus.succeeded, "found", "found-" + item.getIndex());
              }));
    }
    go.countDown();
    CompletableFuture.allOf(ends.toArray(new CompletableFuture[0])).get();

    Query byTaskRunRef = new Query(Criteria.where("taskRunRef").is(gate.getId()));
    awaitEngine("the task after the parent is admitted")
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    awaitEngine("no second admission appears")
        .during(Duration.ofSeconds(2))
        .until(() -> mongoTemplate.count(byTaskRunRef, ActionEntity.class) == 1);
    TaskRunEntity completed = taskRunRepository.findById(parent.getId()).orElseThrow();
    assertEquals(RunStatus.succeeded, completed.getStatus());
    assertEquals(List.of("found-0", "found-1"), resultValue(completed, "found"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** start -> stage -> locate (foreach over items) -> report (always) -> end. */
  private String workflow(String name, Object items) {
    WorkflowTask stage = node("stage", stageRef, "start");
    WorkflowTask locate = node("locate", locateRef, "stage");
    WorkflowTaskForeach foreach = new WorkflowTaskForeach();
    foreach.setItems(items);
    locate.setForeach(foreach);
    WorkflowTask report = node("report", reportRef, "locate");
    report.setParams(new LinkedList<>(List.of(new RunParam("input", "$(tasks.locate.results.found)"))));
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = node("end", null, "report");
    end.setType(TaskType.end);
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    workflow.setTasks(new LinkedList<>(List.of(start, stage, locate, report, end)));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private String submit(String workflowId) {
    return workflowService.submit(workflowId, new WorkflowSubmitRequest(), true).getId();
  }

  private String template(String name, String param, String result) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    if (param != null) {
      AbstractParam declared = new AbstractParam();
      declared.setName(param);
      declared.setType("text");
      task.getSpec().setParams(new LinkedList<>(List.of(declared)));
    }
    if (result != null) {
      ResultSpec declared = new ResultSpec();
      declared.setName(result);
      task.getSpec().setResults(new LinkedList<>(List.of(declared)));
    }
    return taskService.create(task).getId();
  }

  private static WorkflowTask node(String name, String taskRef, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(TaskType.template);
    task.setTaskRef(taskRef);
    task.setDependencies(new LinkedList<>(List.of(dependencyOn(dependsOn))));
    return task;
  }

  private static WorkflowTaskDependency dependencyOn(String taskRef) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    return dependency;
  }

  private TaskRunEntity awaitAdmitted(String runId, String name) {
    awaitEngine(name + " admitted")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.ready,
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef(name, runId)
                        .map(TaskRunEntity::getStatus)
                        .orElse(null)));
    return taskRunRepository.findFirstByNameAndWorkflowRunRef(name, runId).orElseThrow();
  }

  /** End a named task of the run the way a dispatcher does, with at most one result. */
  private void endTask(String runId, String name, RunStatus status, String result, String value) {
    endTaskRun(awaitAdmitted(runId, name).getId(), status, result, value);
  }

  private void endTaskRun(String taskRunId, RunStatus status, String result, String value) {
    TaskRunEndRequest request = new TaskRunEndRequest();
    request.setStatus(status);
    if (result != null) {
      request.setResults(new LinkedList<>(List.of(new RunResult(result, value))));
    }
    taskRunService.end(taskRunId, Optional.of(request));
  }

  private TaskRunEntity awaitParentRunning(String runId) {
    awaitEngine("foreach parent running")
        .untilAsserted(
            () ->
                assertEquals(
                    RunPhase.running,
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("locate", runId)
                        .map(TaskRunEntity::getPhase)
                        .orElse(null)));
    return taskRunRepository.findFirstByNameAndWorkflowRunRef("locate", runId).orElseThrow();
  }

  private TaskRunEntity awaitParentCompleted(String runId) {
    awaitEngine("foreach parent completed")
        .untilAsserted(
            () ->
                assertEquals(
                    RunPhase.completed,
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("locate", runId)
                        .map(TaskRunEntity::getPhase)
                        .orElse(null)));
    return taskRunRepository.findFirstByNameAndWorkflowRunRef("locate", runId).orElseThrow();
  }

  private List<TaskRunEntity> awaitItemsReady(TaskRunEntity parent, int count) {
    awaitEngine(count + " items admitted")
        .untilAsserted(
            () -> {
              List<TaskRunEntity> items =
                  taskRunRepository.findByParentRefOrderByIndexAsc(parent.getId());
              assertEquals(count, items.size());
              items.forEach(item -> assertEquals(RunStatus.ready, item.getStatus(), item.getName()));
            });
    return taskRunRepository.findByParentRefOrderByIndexAsc(parent.getId());
  }

  /** A foreach parent left running with its resolved items recorded, started well before now. */
  private TaskRunEntity savedCrashedParent(WorkflowRunEntity wfRun, List<Object> items) {
    TaskRunEntity parent =
        savedTaskRun(
            "locate", TaskType.template, RunStatus.running, RunPhase.running, wfRun.getWorkflowRef(), wfRun.getId());
    WorkflowTaskForeach foreach = new WorkflowTaskForeach();
    foreach.setItems(items);
    parent.setForeach(foreach);
    parent.setStartTime(new Date(System.currentTimeMillis() - 120000));
    parent.setTaskRef(locateRef);
    parent.setResults(new LinkedList<>(List.of(new RunResult("found", null))));
    parent.setDependencies(List.of(dependencyOn("start")));
    return taskRunRepository.save(parent);
  }

  /** An approval task after the foreach parent, then the end task - admitting it writes one action. */
  private TaskRunEntity savedGateAfterParent(WorkflowRunEntity wfRun) {
    TaskRunEntity gate =
        savedTaskRun(
            "gate", TaskType.approval, RunStatus.notstarted, RunPhase.pending, wfRun.getWorkflowRef(), wfRun.getId());
    gate.setDependencies(List.of(dependencyOn("locate")));
    taskRunRepository.save(gate);
    TaskRunEntity end =
        savedTaskRun(
            "end", TaskType.end, RunStatus.notstarted, RunPhase.pending, wfRun.getWorkflowRef(), wfRun.getId());
    end.setDependencies(List.of(dependencyOn("gate")));
    taskRunRepository.save(end);
    return gate;
  }

  private TaskRunEntity savedItem(
      TaskRunEntity parent, int index, RunStatus status, RunPhase phase, String found) {
    TaskRunEntity item =
        savedTaskRun(
            "locate[" + index + "]", TaskType.template, status, phase, parent.getWorkflowRef(), parent.getWorkflowRunRef());
    item.setParentRef(parent.getId());
    item.setIndex(index);
    item.setResults(new LinkedList<>(List.of(new RunResult("found", found))));
    return taskRunRepository.save(item);
  }

  private static Object paramValue(TaskRunEntity taskRun, String name) {
    return taskRun.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .map(RunParam::getValue)
        .findFirst()
        .orElse(null);
  }

  private static Object resultValue(TaskRunEntity taskRun, String name) {
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .findFirst()
        .orElse(null);
  }
}
