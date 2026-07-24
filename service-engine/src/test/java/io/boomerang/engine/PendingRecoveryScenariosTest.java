package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.boomerang.engine.repository.EventIngressRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Safety-net scenarios from the gap register: pause discipline, submission/event dedup, and the
 * (still pending) tombstone delete model. Sweeps and claims are exercised by direct invocation.
 */
class PendingRecoveryScenariosTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private EventIngressRepository eventIngressRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void pausedRunIsExcludedFromClaimUntilResumeReconciles() {
    String wfRunId = submittedAndStartedRun("pause-lifecycle");
    String echoTaskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow().getId();
    assertTrue(claimPageContains(echoTaskRunId), "ready task should be claimable before pause");

    workflowRunService.pause(wfRunId);
    assertNotNull(
        workflowRunRepository.findById(wfRunId).orElseThrow().getPauseRequestedAt(),
        "pause is a flag, never a status");
    assertEquals(
        RunStatus.running,
        workflowRunRepository.findById(wfRunId).orElseThrow().getStatus(),
        "pause must not change the run status");
    assertFalse(
        claimPageContains(echoTaskRunId), "paused run's tasks are excluded from the claim page");

    workflowRunService.resume(wfRunId);
    assertNull(workflowRunRepository.findById(wfRunId).orElseThrow().getPauseRequestedAt());
    awaitEngine("the resumed task to be claimable again")
        .until(() -> claimPageContains(echoTaskRunId));

    // The resumed run completes through the normal agent callbacks.
    taskRunService.start(echoTaskRunId, Optional.empty());
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    taskRunService.end(echoTaskRunId, Optional.of(endRequest));
    awaitEngine("the resumed run to complete")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.succeeded, run.getStatus());
              assertEquals(RunPhase.completed, run.getPhase());
            });
  }

  @Test
  void pausedRunHoldsGraphAdvanceUntilResume() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("pause-approval-wf", RunStatus.running, RunPhase.running);
    savedTaskRun(
        "start",
        TaskType.start,
        RunStatus.succeeded,
        RunPhase.completed,
        wfRun.getWorkflowRef(),
        wfRun.getId());
    TaskRunEntity gate =
        savedTaskRun(
            "gate",
            TaskType.approval,
            RunStatus.waiting,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    gate.setDependencies(List.of(dependencyOn("start")));
    taskRunRepository.save(gate);
    TaskRunEntity work =
        savedTaskRun(
            "work",
            TaskType.template,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    work.setDependencies(List.of(dependencyOn("gate")));
    taskRunRepository.save(work);
    TaskRunEntity end =
        savedTaskRun(
            "end",
            TaskType.end,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    end.setDependencies(List.of(dependencyOn("work")));
    taskRunRepository.save(end);

    workflowRunService.pause(wfRun.getId());

    // The approval is actioned while paused: the gate itself completes, but the admission
    // chokepoint holds its dependants until resume reconciles.
    TaskRunEndRequest approve = new TaskRunEndRequest();
    approve.setStatus(RunStatus.succeeded);
    taskRunService.end(gate.getId(), Optional.of(approve));
    awaitEngine("the actioned gate to complete")
        .untilAsserted(
            () ->
                assertEquals(
                    RunPhase.completed,
                    taskRunRepository.findById(gate.getId()).orElseThrow().getPhase()));
    awaitEngine("the dependant to stay un-admitted while paused")
        .during(Duration.ofSeconds(3))
        .until(
            () ->
                RunStatus.notstarted.equals(
                    taskRunRepository.findById(work.getId()).orElseThrow().getStatus()));

    workflowRunService.resume(wfRun.getId());
    awaitEngine("resume to reconcile and admit the dependant")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.ready,
                    taskRunRepository.findById(work.getId()).orElseThrow().getStatus()));
  }

  @Test
  void duplicateEventsAndSubmissionsAreDeduplicated() {
    // The loader owns this index in deployments; the test database recreates it as the dedup gate.
    mongoTemplate
        .indexOps(WorkflowRunEntity.class)
        .createIndex(
            new Index()
                .on("idempotencyKey", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(Criteria.where("idempotencyKey").exists(true)))
                .named("idempotency_key"));

    // Duplicate submission: the same idempotencyKey returns the existing run, never a second one.
    String workflowId = createdLifecycleWorkflow("dedup-submit");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setIdempotencyKey("dedup-submit-key");
    String firstRunId = workflowService.submit(workflowId, request, false).getId();
    String secondRunId = workflowService.submit(workflowId, request, false).getId();
    assertEquals(firstRunId, secondRunId, "duplicate submission must return the existing run");

    // Duplicate event delivery: the ingress ledger acknowledges the redelivery without
    // re-applying it - the result is appended exactly once.
    WorkflowRunEntity wfRun =
        savedWorkflowRun("dedup-event-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity listener =
        savedTaskRun(
            "listener",
            TaskType.eventwait,
            RunStatus.ready,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId());
    listener.setParams(List.of(new RunParam("topic", "dedup-topic")));
    taskRunRepository.save(listener);

    WorkflowRunEventRequest event = new WorkflowRunEventRequest();
    event.setId("evt-1");
    event.setTopic("dedup-topic");
    RunResult payload = new RunResult();
    payload.setName("payload");
    payload.setValue("once");
    event.getResults().add(payload);

    workflowRunService.event(wfRun.getId(), event);
    workflowRunService.event(wfRun.getId(), event);

    assertEquals(
        1,
        taskRunRepository.findById(listener.getId()).orElseThrow().getResults().size(),
        "a redelivered event must not re-apply its results");
    assertTrue(
        eventIngressRepository.findById(wfRun.getId() + ":evt-1").isPresent(),
        "the ingress ledger records the delivery");
  }

  // Today only the stopgap guard exists (WorkflowService.delete refuses while runs are in
  // flight). The status-based tombstone + watcher wind-down land in slice F.
  @Disabled("Tombstone/watcher delete model does not exist until slice F - gap-register scenario #9")
  @Test
  void deletedWorkflowIsTombstonedCancelledAndPruned() {
    fail("Implement with slice F: deleted status set; watcher cancels in-flight; retention sweep prunes");
  }

  private String submittedAndStartedRun(String name) {
    String workflowId = createdLifecycleWorkflow(name);
    String wfRunId =
        workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("template TaskRun ready for agent pickup")
        .untilAsserted(
            () -> {
              Optional<TaskRunEntity> taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId);
              assertTrue(taskRun.isPresent());
              assertEquals(RunStatus.ready, taskRun.get().getStatus());
            });
    return wfRunId;
  }

  private String createdLifecycleWorkflow(String name) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask("echo", TaskType.template, templateId, "start"),
            workflowTask("end", TaskType.end, null, "echo")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private boolean claimPageContains(String taskRunId) {
    return taskRunRepository.findClaimable(List.of(TaskType.template), 100).stream()
        .anyMatch(t -> taskRunId.equals(t.getId()));
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }

  private static WorkflowTaskDependency dependencyOn(String taskRef) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    return dependency;
  }
}
