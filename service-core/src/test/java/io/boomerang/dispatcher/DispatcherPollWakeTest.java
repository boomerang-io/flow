package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.engine.AbstractSpiedEngineIntegrationTest;
import io.boomerang.engine.TaskExecutionService;
import io.boomerang.engine.model.ClaimFilter;
import io.boomerang.workflow.WorkflowService;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Admitting a task wakes the polls held on this instance: a poll whose filter matches returns with
 * the task well inside the re-check interval, one whose filter does not match looks again, finds
 * nothing and keeps holding, and a wake that lands while the poll is still looking is not lost.
 * Submitting a run with a workspace wakes the held workflow poll the same way.
 */
class DispatcherPollWakeTest extends AbstractSpiedEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private WorkflowService workflowService;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private PollWakeups pollWakeups;

  private WorkflowRunEntity wfRun;

  @BeforeEach
  void graphAllowsTheTask() {
    doReturn(true).when(dagUtility).canRunTask(anyList(), any());
    cancelTaskRunsEarlierTestsLeftClaimable();
    wfRun = savedWorkflowRun("poll-wake-wf", RunStatus.running, RunPhase.running);
  }

  // A template task run an earlier test left claimable, or claimed and finished, would answer an
  // unfiltered poll at once and the assertions on when a poll returns would measure that leftover
  // instead; they end cancelled with no claim, which neither the claim page nor the termination
  // pages pick up.
  private void cancelTaskRunsEarlierTestsLeftClaimable() {
    mongoTemplate.updateMulti(
        Query.query(
            new Criteria()
                .orOperator(
                    Criteria.where("status").is(RunStatus.ready).and("phase").is(RunPhase.pending),
                    Criteria.where("claim.by").exists(true))),
        new Update()
            .set("status", RunStatus.cancelled)
            .set("phase", RunPhase.completed)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt"),
        TaskRunEntity.class);
  }

  private String registerDispatcher(String name) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name, name + ".local", List.of("template")));
  }

  // A task as the DAG walk hands it to queue: not yet admitted.
  private String unadmittedTask(String name, TaskType type) {
    return savedTaskRun(
            name,
            type,
            RunStatus.notstarted,
            RunPhase.pending,
            wfRun.getWorkflowRef(),
            wfRun.getId())
        .getId();
  }

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  private static boolean containsRun(ResponseEntity<List<WorkflowRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(r -> id.equals(r.getId()));
  }

  // A workflow of one template task with a run workspace, so its runs wait to be provisioned.
  private String workflowWithWorkspace(String name) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                workflowTask("start", TaskType.start, null, null),
                workflowTask("work", TaskType.template, templateId, "start"),
                workflowTask("end", TaskType.end, null, "work"))));
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("1Gi");
    workspace.setMountPath("/workspace");
    workflow.setWorkspace(workspace);
    return workflowService.create(workflow, false).getBody().getId();
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }

  // Runs earlier tests left waiting for provisioning would answer a workflow poll at once; they
  // end cancelled, which no provisioning claim picks up.
  private void cancelRunsEarlierTestsLeftProvisionable() {
    mongoTemplate.updateMulti(
        Query.query(
            Criteria.where("status")
                .is(RunStatus.ready)
                .and("phase")
                .is(RunPhase.pending)
                .and("claim.by")
                .exists(false)
                .and("workspace")
                .exists(true)),
        new Update().set("status", RunStatus.cancelled).set("phase", RunPhase.completed),
        WorkflowRunEntity.class);
  }

  @Test
  void aHeldPollReturnsWithATaskAdmittedFromAnotherThreadAtOnce() throws Exception {
    String dispatcher = registerDispatcher("wake-match");
    String taskId = unadmittedTask("wake-match-task", TaskType.template);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<TaskRun>>> poll =
          executor.submit(() -> dispatcherService.getTaskQueue(dispatcher, null, null, null, null));
      // The poll's first look finds nothing and it is held; the admission lands mid-hold.
      Thread.sleep(100);
      long queuedAt = System.nanoTime();
      taskExecutionService.queue(taskId);

      ResponseEntity<List<TaskRun>> response = poll.get(5, TimeUnit.SECONDS);
      long waitedMs = Duration.ofNanos(System.nanoTime() - queuedAt).toMillis();

      assertTrue(containsId(response, taskId), "the held poll must claim the admitted task");
      // Admission, the wake and the claim; the one-second re-check alone would take about 900 ms.
      assertTrue(waitedMs < 500, "claimed " + waitedMs + " ms after queue, expected < 500");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aHeldPollWhoseFilterDoesNotMatchWakesAndKeepsHolding() throws Exception {
    String dispatcher = registerDispatcher("wake-miss"); // template only
    String taskId = unadmittedTask("wake-miss-task", TaskType.custom);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<TaskRun>>> poll =
          executor.submit(() -> dispatcherService.getTaskQueue(dispatcher, null, null, null, null));
      Thread.sleep(100);
      taskExecutionService.queue(taskId);

      // Within half the re-check interval the poll has looked twice: at entry and on the wake.
      awaitEngine("the task to be admitted")
          .until(
              () ->
                  RunStatus.ready.equals(
                      taskRunRepository.findById(taskId).orElseThrow().getStatus()));
      assertThrows(TimeoutException.class, () -> poll.get(400, TimeUnit.MILLISECONDS));
      verify(spiedTaskRunService, atLeast(2)).findClaimable(any(ClaimFilter.class), anyInt());
      assertFalse(poll.isDone(), "a poll that found nothing on the wake keeps holding");

      executor.shutdownNow();
      assertEquals(HttpStatus.NO_CONTENT, poll.get(2, TimeUnit.SECONDS).getStatusCode());
      TaskRunEntity after = taskRunRepository.findById(taskId).orElseThrow();
      assertEquals(RunStatus.ready, after.getStatus(), "the task stays claimable for its own type");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aWakeThatLandsWhileThePollIsLookingIsNotLost() throws Exception {
    String dispatcher = registerDispatcher("wake-during-look");
    AtomicBoolean admitted = new AtomicBoolean();
    String[] taskId = new String[1];
    // The admission lands inside the poll's first look: after the claim query ran and before the
    // poll waits. The generation read before the look is what makes the wait return at once.
    doAnswer(
            invocation -> {
              Object page = invocation.callRealMethod();
              if (admitted.compareAndSet(false, true)) {
                taskId[0] =
                    savedTaskRun(
                            "wake-during-look-task",
                            TaskType.template,
                            RunStatus.ready,
                            RunPhase.pending,
                            wfRun.getWorkflowRef(),
                            wfRun.getId())
                        .getId();
                pollWakeups.signal();
              }
              return page;
            })
        .when(spiedTaskRunService)
        .findClaimable(any(ClaimFilter.class), anyInt());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      long polledAt = System.nanoTime();
      Future<ResponseEntity<List<TaskRun>>> poll =
          executor.submit(() -> dispatcherService.getTaskQueue(dispatcher, null, null, null, null));
      ResponseEntity<List<TaskRun>> response = poll.get(5, TimeUnit.SECONDS);
      long waitedMs = Duration.ofNanos(System.nanoTime() - polledAt).toMillis();

      assertTrue(containsId(response, taskId[0]), "the poll must claim the task on its next look");
      // A lost wake would hold the poll for the whole one-second re-check interval.
      assertTrue(
          waitedMs < 500, "claimed " + waitedMs + " ms after the poll began, expected < 500");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aHeldWorkflowPollReturnsWithARunSubmittedWithAWorkspaceAtOnce() throws Exception {
    String dispatcher = registerDispatcher("wake-provision");
    String workflowId = workflowWithWorkspace("wake-provision-wf");
    cancelRunsEarlierTestsLeftProvisionable();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<WorkflowRun>>> poll =
          executor.submit(() -> dispatcherService.getWorkflowQueue(dispatcher));
      // The poll's first look finds nothing and it is held; the submit lands mid-hold.
      Thread.sleep(100);
      long submittedAt = System.nanoTime();
      String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), true).getId();

      ResponseEntity<List<WorkflowRun>> response = poll.get(5, TimeUnit.SECONDS);
      long waitedMs = Duration.ofNanos(System.nanoTime() - submittedAt).toMillis();

      assertTrue(containsRun(response, runId), "the held poll must claim the run to provision");
      // The submit, the wake and the claim; the one-second re-check alone would take about 900 ms.
      assertTrue(waitedMs < 600, "claimed " + waitedMs + " ms after submit, expected < 600");
    } finally {
      executor.shutdownNow();
    }
  }
}
