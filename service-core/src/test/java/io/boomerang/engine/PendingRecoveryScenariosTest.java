package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.boomerang.event.repository.EventInboxRepository;
import io.boomerang.engine.repository.WorkflowRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Safety-net scenarios from the gap register: pause discipline, event-delivery dedup, and the
 * (still pending) tombstone delete model. Sweeps and claims are exercised by direct invocation.
 */
class PendingRecoveryScenariosTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private EventInboxRepository eventInboxRepository;
  @Autowired private WorkflowRepository workflowRepository;
  @Autowired private WorkflowWatcher watcher;

  @Test
  void readyTaskStaysClaimableWhenRunPauses() {
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
    // Pause gates admission of new tasks, not the claiming of work already in flight. A task
    // already ready when the run pauses stays claimable and runs to completion; the run then
    // holds at the admission gate rather than advancing to new tasks (see the graph-advance test).
    assertTrue(
        claimPageContains(echoTaskRunId),
        "a task already ready when the run pauses stays claimable");

    workflowRunService.resume(wfRunId);
    assertNull(workflowRunRepository.findById(wfRunId).orElseThrow().getPauseRequestedAt());
    assertTrue(claimPageContains(echoTaskRunId));

    // The run completes through the normal agent callbacks.
    taskRunService.start(echoTaskRunId, Optional.empty());
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    taskRunService.end(echoTaskRunId, Optional.of(endRequest));
    awaitEngine("the run to complete")
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
  void duplicateEventDeliveryIsDeduplicated() {
    // Duplicate event delivery: the inbox ledger acknowledges the redelivery without
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
        eventInboxRepository.findById(wfRun.getId() + ":evt-1").isPresent(),
        "the inbox ledger records the delivery");
  }

  @Test
  void deletedWorkflowIsTombstonedAndRunsCancelled() {
    String workflowId = createdLifecycleWorkflow("tombstone");
    String wfRunId =
        workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("run in flight")
        .untilAsserted(
            () ->
                assertTrue(
                    workflowRunRepository.existsByWorkflowRefAndPhaseIn(
                        workflowId,
                        List.of(RunPhase.pending, RunPhase.queued, RunPhase.running))));

    // Delete tombstones the Workflow - no cascade, nothing destroyed.
    workflowService.delete(workflowId);
    WorkflowEntity tombstoned = workflowRepository.findById(workflowId).orElseThrow();
    assertEquals(WorkflowStatus.deleted, tombstoned.getStatus());

    // The watcher winds down the in-flight run.
    watcher.cancelDeletedWorkflowRuns();
    awaitEngine("the in-flight run of the deleted workflow to be cancelled")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.cancelled,
                    workflowRunRepository.findById(wfRunId).orElseThrow().getStatus()));

    // Pruning ships disabled, so the workflow document survives (never hard-deleted).
    watcher.pruneDeletedWorkflows();
    assertTrue(workflowRepository.findById(workflowId).isPresent());
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
    return taskRunService.findClaimable(List.of(TaskType.template), 100).stream()
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
