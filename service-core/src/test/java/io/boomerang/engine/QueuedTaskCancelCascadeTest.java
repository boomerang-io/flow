package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.Task;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.WorkflowService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A WorkflowRun cancel must reach a TaskRun that is claimed but not yet started ({@code queued}),
 * not only {@code running} ones - otherwise the run goes terminal while its claimed task is
 * orphaned forever. The cascade cancels the task outright (unfenced by claim identity, since
 * cancellation is authoritative); the claimant's eventual start/end is then rejected by the
 * ordinary phase checks once the task is already {@code completed}.
 */
class QueuedTaskCancelCascadeTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private DispatcherService dispatcherService;

  @Test
  void cancellingARunEndsAQueuedClaimedTaskAndFencesTheLateDispatcher() {
    String workflowId = createdLifecycleWorkflow("cancel-queued");
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("template TaskRun ready for agent pickup")
        .untilAsserted(
            () -> {
              Optional<TaskRunEntity> t =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId);
              assertTrue(t.isPresent());
              assertEquals(RunStatus.ready, t.get().getStatus());
            });
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow().getId();

    String agentId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "cancel-cascade-agent", "cancel-cascade-agent.local", List.of("template")));
    List<TaskRun> claimed = dispatcherService.getTaskQueue(agentId).getBody();
    assertTrue(
        claimed != null && claimed.stream().anyMatch(t -> taskRunId.equals(t.getId())),
        "the agent must have claimed the task ahead of the cancel");
    assertEquals(RunPhase.queued, taskRunRepository.findById(taskRunId).orElseThrow().getPhase());
    Long claimSeq = taskRunRepository.findById(taskRunId).orElseThrow().getClaim().getSeq();

    workflowRunService.cancel(wfRunId);

    awaitEngine("the queued (claimed but unstarted) task to be cancelled by the run cancel")
        .untilAsserted(
            () -> {
              TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
              assertEquals(RunStatus.cancelled, after.getStatus());
              assertEquals(RunPhase.completed, after.getPhase());
            });
    assertEquals(RunStatus.cancelled, workflowRunRepository.findById(wfRunId).orElseThrow().getStatus());

    // The dispatcher's eventual start arrives after the cascade - rejected outright since the
    // TaskRun is already completed, with no need for claim.seq bookkeeping to catch it.
    taskExecutionService.start(taskRunId, Optional.of(agentId), Optional.of(claimSeq));
    TaskRunEntity stillCancelled = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunPhase.completed, stillCancelled.getPhase());
    assertEquals(RunStatus.cancelled, stillCancelled.getStatus());
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

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      io.boomerang.common.model.WorkflowTaskDependency dependency =
          new io.boomerang.common.model.WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }
}
