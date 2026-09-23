package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.common.model.WorkflowWorkspaceSpec;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A task mounts only the workspaces it declares, never every workspace the run carries. The run
 * declares two; the node declares one; that one subset is what the materialised TaskRun holds,
 * what the dispatcher's claim payload carries (the pod is built from it), and what survives the
 * asynchronous execute() the dispatcher's start call triggers.
 */
class TaskWorkspaceScopeTest extends AbstractEngineIntegrationTest {

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private DispatcherService dispatcherService;

  @BeforeEach
  void seedGraphRoot() {
    seedRelationshipRoot();
  }

  @Test
  void aTaskKeepsOnlyItsDeclaredWorkspaceThroughClaimAndExecute() {
    Task template = new Task();
    template.setName("workspace-scope-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();
    assertNotNull(templateId);

    // The node declares the run-scoped workspace only - the workflow-scoped one is on the run but
    // not on this task.
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");
    TaskWorkspace declared = new TaskWorkspace();
    declared.setName("workflowrun");
    declared.setType("workflowrun");
    declared.setMountPath("/workspace/run");
    echo.setWorkspaces(new LinkedList<>(List.of(declared)));

    Workflow workflow = new Workflow();
    workflow.setName("task-workspace-scope");
    workflow.setWorkspaces(
        new LinkedList<>(
            List.of(
                runWorkspace("workflow", "workflow", "/workspace/shared"),
                runWorkspace("workflowrun", "workflowrun", "/workspace/run"))));
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            echo,
            node("end", TaskType.end, null, "echo")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    // A run with workspaces parks for dispatcher provisioning, so the dispatcher's start call is
    // what walks the graph (decision 0067).
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

    // Materialised at queue time from the node's own declaration.
    assertDeclaredSubset(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspaces(),
        "the materialised TaskRun should hold only the workspace the node declares");

    // The claim payload is what the dispatcher builds the pod from.
    String agentId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "workspace-scope-zone", "workspace-scope-zone.local", List.of("template")));
    List<TaskRun> queue = dispatcherService.getTaskQueue(agentId).getBody();
    assertNotNull(queue, "the dispatcher's queue should carry the claimed TaskRun");
    TaskRun claimed =
        queue.stream().filter(t -> taskRunId.equals(t.getId())).findFirst().orElse(null);
    assertNotNull(claimed, "the dispatcher's queue should carry the claimed TaskRun");
    assertDeclaredSubset(
        claimed.getWorkspaces(),
        "the dispatcher's claim payload should carry only the workspace the node declares");

    // The dispatcher's start call runs execute() asynchronously, after it already snapshotted the
    // claim: whatever execute() writes must not widen the TaskRun's workspaces.
    taskRunService.start(taskRunId, Optional.empty());
    awaitEngine("echo TaskRun running")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.running,
                    taskRunRepository.findById(taskRunId).orElseThrow().getStatus()));
    Awaitility.await("echo TaskRun keeps its declared workspace through execute")
        .atMost(Duration.ofSeconds(10))
        .during(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () ->
                assertDeclaredSubset(
                    taskRunRepository.findById(taskRunId).orElseThrow().getWorkspaces(),
                    "execute() must not replace the TaskRun's workspaces with the run's"));
  }

  private static void assertDeclaredSubset(List<TaskWorkspace> workspaces, String message) {
    assertNotNull(workspaces, message);
    assertEquals(
        1,
        workspaces.size(),
        message + " but held " + workspaces.stream().map(TaskWorkspace::getName).toList());
    assertTrue(
        workspaces.stream().anyMatch(ws -> "workflowrun".equals(ws.getName())), message);
    assertEquals("/workspace/run", workspaces.get(0).getMountPath());
  }

  private static WorkflowWorkspace runWorkspace(String name, String type, String mountPath) {
    WorkflowWorkspace ws = new WorkflowWorkspace();
    ws.setName(name);
    ws.setType(type);
    WorkflowWorkspaceSpec spec = new WorkflowWorkspaceSpec();
    spec.setMountPath(mountPath);
    spec.setSize("1Gi");
    ws.setSpec(spec);
    return ws;
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
