package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.boomerang.engine.repository.TaskRunRepository;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Q-127 residue — handler paths that still write a WHOLE stale document instead of a
 * Compare-And-Set, so a transition another caller already committed is silently rolled back.
 *
 * <p>Both tests drive the REAL handler. The concurrent writer is injected at a seam the handler
 * genuinely crosses (a database round trip between its entry read and its save), which is exactly
 * where a second instance's write lands in production; nothing about the handler is stubbed out.
 */
class StaleSnapshotSaveTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private WorkflowRunService workflowRunService;

  @Autowired private WorkflowRunStateService workflowRunStateService;
  @Autowired private MongoTemplate mongoTemplate;

  @MockitoSpyBean private DAGUtility dagUtility;
  @MockitoSpyBean private TaskRunService spiedTaskRunService;
  @MockitoSpyBean private TaskRunRepository spiedTaskRunRepository;

  /**
   * Q-127 #6/#11 — the admission Compare-And-Set was supposed to make a join queued by both
   * parents harmless. It does on the {@code canRunTask == true} branch ({@code
   * TaskExecutionService.java:153}); the {@code else} branch at {@code
   * TaskExecutionService.java:169-171} never got one. It writes the whole entity read at handler
   * entry ({@code :91}) back as {@code skipped}, so a TaskRun the other parent already admitted
   * and started is reverted to {@code pending}, loses its {@code startTime}/{@code timeoutAt}, and
   * is then completed as {@code skipped} by the {@code end()} that follows.
   */
  @Test
  void queueSkipPathMustNotOverwriteATaskRunAnotherCallerAlreadyStarted() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("skip-path-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity join =
        savedTaskRun(
            "join",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    String joinId = join.getId();

    // The other parent's queue() wins admission and the task starts, landing between this
    // caller's entry read and its skip-path save.
    doAnswer(
            invocation -> {
              taskRunService.tryAdmit(joinId, List.of(), new TaskRunSpec());
              taskRunService.tryStartExecution(joinId, new Date(), 0L);
              return false;
            })
        .when(dagUtility)
        .canRunTask(anyList(), any());

    taskExecutionService.queue(joinId);

    awaitEngine("queue() to finish its skip path")
        .until(() -> !RunStatus.notstarted.equals(current(joinId).getStatus()));

    TaskRunEntity after = current(joinId);
    assertEquals(
        RunStatus.running,
        after.getStatus(),
        "a TaskRun another caller already admitted and started must not be overwritten as skipped");
    assertEquals(RunPhase.running, after.getPhase(), "the started phase must not be rolled back");
    assertNotNull(after.getStartTime(), "the start time written by tryStartExecution was lost");
  }

  /**
   * The skip is two writes - the Compare-And-Set, then {@code end()}. A caller that dies in between
   * leaves the TaskRun at {@code status=skipped, phase=pending}, and the graph advance re-drives it
   * through {@code queue()} ({@code TaskExecutionService:1042}, {@code WorkflowWatcher:305}). The
   * skip Compare-And-Set must therefore accept {@code skipped} as well as {@code notstarted}:
   * matching only {@code notstarted} would fail the re-drive's CAS, {@code end()} would never be
   * called, and the run would stall permanently - a regression versus the unguarded save.
   */
  @Test
  void requeueOfAnAlreadySkippedButUncompletedTaskMustStillEndIt() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("skip-redrive-wf", RunStatus.running, RunPhase.running);
    // Exactly the state a caller that died between the skip write and end() leaves behind.
    TaskRunEntity orphan =
        savedTaskRun(
            "orphaned-skip",
            TaskType.template,
            RunStatus.skipped,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    String orphanId = orphan.getId();

    doAnswer(invocation -> false).when(dagUtility).canRunTask(anyList(), any());

    taskExecutionService.queue(orphanId);

    awaitEngine("the re-driven skip to complete")
        .until(() -> RunPhase.completed.equals(current(orphanId).getPhase()));

    TaskRunEntity after = current(orphanId);
    assertEquals(RunStatus.skipped, after.getStatus(), "the re-drive must keep the skipped status");
    assertEquals(
        RunPhase.completed,
        after.getPhase(),
        "a skipped-but-pending TaskRun must still be ended by a re-drive, not stall the run");
  }

  /**
   * The guard must not break the ordinary case: a task that genuinely cannot run is still marked
   * skipped, completed by {@code end()}, and its dependants are still queued.
   */
  @Test
  void genuineSkipStillEndsTheTaskAndAdvancesTheGraph() {
    WorkflowRunEntity wfRun = savedWorkflowRun("skip-advance-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity skipped =
        savedTaskRun(
            "skipme",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    TaskRunEntity dependant =
        savedTaskRun(
            "next",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef("skipme");
    dependant.setDependencies(new LinkedList<>(List.of(dependency)));
    taskRunRepository.save(dependant);
    String skippedId = skipped.getId();
    String dependantId = dependant.getId();

    // "skipme" has no valid path; its dependant does once "skipme" completes.
    doAnswer(
            invocation ->
                !"skipme".equals(((TaskRunEntity) invocation.getArgument(1)).getName()))
        .when(dagUtility)
        .canRunTask(anyList(), any());

    taskExecutionService.queue(skippedId);

    awaitEngine("the skipped task to complete and the graph to advance")
        .until(() -> RunStatus.ready.equals(current(dependantId).getStatus()));

    TaskRunEntity after = current(skippedId);
    assertEquals(RunStatus.skipped, after.getStatus(), "a genuine skip must still be marked skipped");
    assertEquals(RunPhase.completed, after.getPhase(), "a genuine skip must still be ended");
  }

  /**
   * {@code setwfstatus} is the one task type that still writes the WorkflowRun with a full-document
   * save ({@code TaskExecutionService.java:588-595}) using the entity read at {@code execute()}
   * entry ({@code :257}). Every field a concurrent Compare-And-Set wrote in between — a result
   * pushed by a parallel {@code setwfproperty}, {@code isAwaitingApproval}, {@code
   * pauseRequestedAt}, {@code phase}/{@code status} — is rolled back. Not one of the audit's 31
   * handlers; the same F1 defect, missed.
   */
  @Test
  void setWorkflowStatusMustNotRollBackConcurrentWorkflowRunWrites() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("setwfstatus-wf", RunStatus.running, RunPhase.running);
    String wfRunId = wfRun.getId();
    TaskRunEntity task =
        savedTaskRun(
            "set-status",
            TaskType.setwfstatus,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRunId);
    task.setParams(List.of(new RunParam("status", "failed", ParamType.string)));
    taskRunRepository.save(task);

    // A parallel branch's setwfproperty pushes a result during the execution-entry Compare-And-Set
    // - i.e. after execute() read the WorkflowRun at :257 and before it saves it back at :593.
    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (injected.compareAndSet(false, true)) {
                workflowRunStateService.appendResult(wfRunId, new RunResult("artifact", "build-42"));
              }
              return invocation.callRealMethod();
            })
        .when(spiedTaskRunService)
        .tryStartExecution(any(), any(), any());

    taskExecutionService.execute(task.getId());

    awaitEngine("the setwfstatus task to complete")
        .until(() -> RunPhase.completed.equals(current(task.getId()).getPhase()));

    List<RunResult> results = workflowRunRepository.findById(wfRunId).orElseThrow().getResults();
    assertEquals(
        1,
        results.stream().filter(r -> "artifact".equals(r.getName())).count(),
        "the concurrently appended WorkflowRun result was rolled back by the full-document save;"
            + " got "
            + results);
  }

  /**
   * {@code WorkflowRunService.event}'s non-waiting branch mutated a TaskRun it had read from the
   * {@code findByWorkflowRunRef} page earlier in the method and saved the WHOLE document back, so a
   * Compare-And-Set that landed in between — here the execution-entry transition to {@code
   * running} — was silently rolled back. The delivery is now three field-scoped operators.
   *
   * <p>Also pins the Mongo escaping of the {@code boomerang.io/status} annotation key: {@code
   * MongoConfiguration.setMapKeyDotReplacement("#")} escapes dots in map keys on a whole-document
   * write and unescapes them on read, but does NOT rewrite an {@code Update}'s field paths — so the
   * update writes {@code annotations.boomerang#io/status} itself. An unescaped path would create a
   * nested {@code boomerang} sub-document that {@code processWaitForEventTask} would never find.
   */
  @Test
  void eventDeliveryMustNotRollBackConcurrentTaskRunWrites() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("event-stale-save-wf", RunStatus.running, RunPhase.running);
    String wfRunId = wfRun.getId();
    TaskRunEntity task =
        savedTaskRun(
            "wait-for-build",
            TaskType.eventwait,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRunId);
    task.setParams(List.of(new RunParam("topic", "build-complete", ParamType.string)));
    taskRunRepository.save(task);
    String taskRunId = task.getId();

    // The TaskRun starts executing between event()'s page read and its write - exactly where a
    // second instance's Compare-And-Set lands in production. The page is read first (the same
    // documents the derived query returns; a Spring Data proxy has no real method to call), then
    // the transition lands, so event() is handed a snapshot that is already stale.
    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(
            invocation -> {
              List<TaskRunEntity> page =
                  mongoTemplate.find(
                      Query.query(Criteria.where("workflowRunRef").is(wfRunId)),
                      TaskRunEntity.class);
              if (injected.compareAndSet(false, true)) {
                taskRunService.tryStartExecution(taskRunId, new Date(), 0L);
              }
              return page;
            })
        .when(spiedTaskRunRepository)
        .findByWorkflowRunRef(wfRunId);

    WorkflowRunEventRequest request = new WorkflowRunEventRequest();
    request.setTopic("build-complete");
    request.setStatus(RunStatus.succeeded);
    request.setResults(List.of(new RunResult("data", "{\"build\":\"ok\"}")));
    workflowRunService.event(wfRunId, request);

    TaskRunEntity after = current(taskRunId);
    assertEquals(
        RunStatus.running,
        after.getStatus(),
        "the concurrently started TaskRun was rolled back by the full-document save");
    assertEquals(RunPhase.running, after.getPhase(), "the started phase must not be rolled back");
    assertNotNull(after.getStartTime(), "the start time written by tryStartExecution was lost");

    // The delivery itself must still have landed, on all three fields.
    assertTrue(after.isPreApproved(), "the delivery must still mark the TaskRun pre-approved");
    assertEquals(
        RunStatus.succeeded.getStatus(),
        after.getAnnotations().get("boomerang.io/status"),
        "the delivered status annotation must read back under its unescaped key");
    assertEquals(
        1,
        after.getResults().stream().filter(r -> "data".equals(r.getName())).count(),
        "the delivered result must still be appended; got " + after.getResults());

    // The stored document, unmapped: the key is escaped, and nothing was written as a nested path.
    Document stored =
        mongoTemplate.execute(
            TaskRunEntity.class,
            collection -> collection.find(new Document("workflowRunRef", wfRunId)).first());
    assertNotNull(stored, "the TaskRun document should exist");
    Document annotations = stored.get("annotations", Document.class);
    assertEquals(
        RunStatus.succeeded.getStatus(),
        annotations.getString("boomerang#io/status"),
        "the annotation key must be stored dot-escaped; got " + annotations);
    assertFalse(
        annotations.containsKey("boomerang"),
        "an unescaped update path would nest the key under a 'boomerang' sub-document; got "
            + annotations);
  }

  private TaskRunEntity current(String id) {
    return taskRunRepository.findById(id).orElseThrow();
  }
}
