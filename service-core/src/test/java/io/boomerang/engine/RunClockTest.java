package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * A run's timeout bounds the time it spends executing, not the time it spends waiting. The clock
 * stops while every in-flight task is waiting and restarts when work resumes, with the deadline
 * moved forward by the time stopped; a pause is treated the same way. A wait is indefinite unless
 * its task declares a timeout of its own, which the task sweep enforces.
 */
class RunClockTest extends AbstractEngineIntegrationTest {

  private static final long MINUTE = 60_000L;

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowWatcher watcher;
  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void aRunIdleAtAGateIsNotReapedAndKeepsItsBudgetForAfterTheGate() {
    String wfRunId = startedRun(gatedWorkflow("clock-gate", null, false), 1L);
    awaitEngine("the gate to be waiting with the run clock stopped")
        .untilAsserted(
            () -> {
              assertEquals(RunStatus.waiting, task("gate", wfRunId).getStatus());
              assertNotNull(run(wfRunId).getWaitingSince());
            });
    assertNull(task("gate", wfRunId).getTimeoutAt(), "a gate without a timeout has no deadline");

    // Two minutes at the gate on a one-minute budget: the deadline as stamped has passed.
    Date now = new Date();
    setRun(wfRunId, new Update()
        .set("waitingSince", new Date(now.getTime() - 2 * MINUTE))
        .set("timeoutAt", new Date(now.getTime() - MINUTE)));
    watcher.reapWorkflowTimeouts();
    watcher.reapTaskTimeouts();
    awaitEngine("the run to stay running through the wait")
        .during(Duration.ofSeconds(2))
        .until(() -> RunPhase.running.equals(run(wfRunId).getPhase()));
    assertEquals(RunStatus.waiting, task("gate", wfRunId).getStatus());

    approve(task("gate", wfRunId).getId());

    awaitEngine("the clock to restart with the deadline moved by the time waited")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = run(wfRunId);
              assertNull(after.getWaitingSince());
              long remaining = after.getTimeoutAt().getTime() - System.currentTimeMillis();
              assertTrue(remaining > 45_000 && remaining < 75_000, "remaining " + remaining);
              assertEquals(RunStatus.ready, task("work", wfRunId).getStatus());
            });
  }

  @Test
  void aRunWithABranchStillExecutingIsReapedAtItsDeadline() {
    String wfRunId = startedRun(gatedWorkflow("clock-branch", null, true), 1L);
    awaitEngine("the gate to be waiting while the other branch is admitted")
        .untilAsserted(
            () -> {
              assertEquals(RunStatus.waiting, task("gate", wfRunId).getStatus());
              assertEquals(RunStatus.ready, task("side", wfRunId).getStatus());
            });
    assertNull(run(wfRunId).getWaitingSince(), "an executing branch keeps the clock running");

    setRun(wfRunId, new Update().set("timeoutAt", new Date(System.currentTimeMillis() - 1000)));
    watcher.reapWorkflowTimeouts();

    awaitEngine("the run to be timed out")
        .untilAsserted(() -> assertEquals(RunStatus.timedout, run(wfRunId).getStatus()));
  }

  @Test
  void aRunPausedPastItsBudgetIsNotReapedOnResume() {
    WorkflowRunEntity wfRun = savedWorkflowRun("clock-pause", RunStatus.running, RunPhase.running);
    Date now = new Date();
    assertTrue(workflowRunStateHelper.tryPause(wfRun.getId()));
    setRun(wfRun.getId(), new Update()
        .set("pauseRequestedAt", new Date(now.getTime() - 2 * MINUTE))
        .set("timeoutAt", new Date(now.getTime() - MINUTE)));

    assertTrue(workflowRunStateHelper.tryResume(wfRun.getId()));
    watcher.reapWorkflowTimeouts();

    WorkflowRunEntity after = run(wfRun.getId());
    assertNull(after.getPauseRequestedAt());
    long remaining = after.getTimeoutAt().getTime() - System.currentTimeMillis();
    assertTrue(remaining > 45_000 && remaining < 75_000, "remaining " + remaining);
    awaitEngine("the resumed run to stay running")
        .during(Duration.ofSeconds(2))
        .until(() -> RunPhase.running.equals(run(wfRun.getId()).getPhase()));
  }

  @Test
  void aGateWithItsOwnTimeoutIsTimedOutByTheTaskSweep() {
    String wfRunId = startedRun(gatedWorkflow("clock-gate-timeout", 5L, false), 1L);
    awaitEngine("the gate to be waiting on its own five-minute deadline")
        .untilAsserted(
            () -> {
              TaskRunEntity gate = task("gate", wfRunId);
              assertEquals(RunStatus.waiting, gate.getStatus());
              assertNotNull(gate.getTimeoutAt());
              long remaining = gate.getTimeoutAt().getTime() - System.currentTimeMillis();
              assertTrue(remaining > 4 * MINUTE && remaining < 6 * MINUTE, "remaining " + remaining);
            });
    String gateId = task("gate", wfRunId).getId();
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(gateId)),
        new Update().set("timeoutAt", new Date(System.currentTimeMillis() - 1000)),
        TaskRunEntity.class);

    watcher.reapTaskTimeouts();

    awaitEngine("the gate and then the run to time out")
        .untilAsserted(
            () -> {
              assertEquals(RunStatus.timedout, taskRunRepository.findById(gateId).orElseThrow().getStatus());
              assertEquals(RunStatus.timedout, run(wfRunId).getStatus());
            });
  }

  @Test
  void aRunsDurationIsItsExecutionTimeWithoutTheWait() {
    String wfRunId = startedRun(gatedWorkflow("clock-duration", null, false), 10L);
    awaitEngine("the gate to be waiting with the run clock stopped")
        .untilAsserted(() -> assertNotNull(run(wfRunId).getWaitingSince()));

    // Started 200 s ago on a ten-minute budget, idle at the gate for the last 120 s.
    Date now = new Date();
    Date startTime = new Date(now.getTime() - 200_000);
    setRun(wfRunId, new Update()
        .set("startTime", startTime)
        .set("waitingSince", new Date(now.getTime() - 120_000))
        .set("timeoutAt", RunTimeouts.deadline(startTime, 10L)));

    approve(task("gate", wfRunId).getId());
    awaitEngine("the work task to be admitted")
        .untilAsserted(() -> assertEquals(RunStatus.ready, task("work", wfRunId).getStatus()));
    String workId = task("work", wfRunId).getId();
    taskRunService.start(workId, Optional.empty());
    taskRunService.end(workId, Optional.of(succeeded()));

    awaitEngine("the run to complete with only its execution time as duration")
        .untilAsserted(
            () -> {
              WorkflowRunEntity after = run(wfRunId);
              assertEquals(RunPhase.completed, after.getPhase());
              assertTrue(
                  after.getDuration() > 70_000 && after.getDuration() < 100_000,
                  "duration " + after.getDuration());
            });
  }

  private String startedRun(Workflow workflow, long timeoutMinutes) {
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTimeout(timeoutMinutes);
    String wfRunId = workflowService.submit(workflowId, request, false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    return wfRunId;
  }

  // start -> gate (approval) -> work (template) -> end; with a side branch, start -> side
  // (template) -> end as well, so a task is executing while the gate waits.
  private Workflow gatedWorkflow(String name, Long gateTimeout, boolean sideBranch) {
    Task approval = new Task();
    approval.setName(name + "-approval");
    approval.setType(TaskType.approval);
    String approvalId = taskService.create(approval).getId();
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    WorkflowTask gate = workflowTask("gate", TaskType.approval, approvalId, "start");
    gate.setTimeout(gateTimeout);
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        sideBranch
            ? List.of(
                workflowTask("start", TaskType.start, null),
                gate,
                workflowTask("work", TaskType.template, templateId, "gate"),
                workflowTask("side", TaskType.template, templateId, "start"),
                workflowTask("end", TaskType.end, null, "work", "side"))
            : List.of(
                workflowTask("start", TaskType.start, null),
                gate,
                workflowTask("work", TaskType.template, templateId, "gate"),
                workflowTask("end", TaskType.end, null, "work")));
    return workflow;
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

  // The same engine end an approval action resolves through.
  private void approve(String taskRunId) {
    taskRunService.end(taskRunId, Optional.of(succeeded()));
  }

  private static TaskRunEndRequest succeeded() {
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    return endRequest;
  }

  private WorkflowRunEntity run(String wfRunId) {
    return workflowRunRepository.findById(wfRunId).orElseThrow();
  }

  private TaskRunEntity task(String name, String wfRunId) {
    return taskRunRepository.findFirstByNameAndWorkflowRunRef(name, wfRunId).orElseThrow();
  }

  private void setRun(String wfRunId, Update update) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(wfRunId)), update, WorkflowRunEntity.class);
  }
}
