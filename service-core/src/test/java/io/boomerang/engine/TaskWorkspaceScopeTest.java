package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The TaskRun's copy of the run's workspace is settled once, at queue time, and is what the
 * materialised TaskRun holds, what the dispatcher's claim payload carries (the pod is built from
 * it), and what survives the asynchronous execute() the dispatcher's start call triggers.
 */
class TaskWorkspaceScopeTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private DispatcherService dispatcherService;

  @Test
  void aTaskKeepsItsSettledWorkspaceThroughClaimAndExecute() {
    Task template = new Task();
    template.setName("workspace-scope-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();
    assertNotNull(templateId);

    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");

    Workflow workflow = new Workflow();
    workflow.setName("task-workspace-scope");
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("1Gi");
    workspace.setMountPath("/workspace/run");
    workspace.setClassName("fast");
    workspace.setAccessMode("ReadWriteMany");
    workflow.setWorkspace(workspace);
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            echo,
            node("end", TaskType.end, null, "echo")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    // A run with a workspace parks for dispatcher provisioning, so the dispatcher's start call is
    // what walks the graph.
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());

    awaitEngine("echo TaskRun claimable")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, taskRun.getStatus());
              assertEquals(RunPhase.pending, taskRun.getPhase());
            });
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow().getId();

    // Materialised at queue time from the run's workspace.
    assertSettled(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
        "the materialised TaskRun should hold the run's workspace");

    // The claim payload is what the dispatcher builds the pod from.
    String agentId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "workspace-scope-zone", "workspace-scope-zone.local", List.of("template")));
    List<TaskRun> queue = dispatcherService.getTaskQueue(agentId, null, null, null, null).getBody();
    assertNotNull(queue, "the dispatcher's queue should carry the claimed TaskRun");
    TaskRun claimed =
        queue.stream().filter(t -> taskRunId.equals(t.getId())).findFirst().orElse(null);
    assertNotNull(claimed, "the dispatcher's queue should carry the claimed TaskRun");
    assertSettled(
        claimed.getWorkspace(), "the dispatcher's claim payload should carry the run's workspace");

    // The dispatcher's start call runs execute() asynchronously, after it already snapshotted the
    // claim: whatever execute() writes must not alter the TaskRun's workspace.
    taskRunService.start(taskRunId, Optional.empty());
    awaitEngine("echo TaskRun running")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.running,
                    taskRunRepository.findById(taskRunId).orElseThrow().getStatus()));
    Awaitility.await("echo TaskRun keeps its workspace through execute")
        .atMost(Duration.ofSeconds(10))
        .during(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () ->
                assertSettled(
                    taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
                    "execute() must not alter the TaskRun's workspace"));
  }

  private static void assertSettled(WorkflowWorkspace workspace, String message) {
    assertNotNull(workspace, message);
    assertEquals("1Gi", workspace.getSize(), message);
    assertEquals("/workspace/run", workspace.getMountPath(), message);
    assertEquals("fast", workspace.getClassName(), message);
    assertEquals("ReadWriteMany", workspace.getAccessMode(), message);
  }

  private static WorkflowTask node(
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
}
