package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
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
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A Task mounts the run's one workspace unless it opts out: an absent or {@code true} {@code
 * workspace} on the node mounts it (the canvas offers no way to opt out, so that is the ordinary
 * case), {@code false} mounts nothing. The TaskRun carries its own copy with the mountPath settled
 * - the workflow's own value, or the default when the workflow names none.
 */
class TaskWorkspaceInheritanceTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  private String templateId;

  @BeforeEach
  void seedGraphRootAndTemplate() {
    Task template = new Task();
    template.setName("workspace-inheritance-echo-" + System.nanoTime());
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    templateId = taskService.create(template).getId();
    assertNotNull(templateId);
  }

  @Test
  void aTaskSayingNothingAboutTheWorkspaceMountsTheRuns() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");

    String workflowId =
        createWorkflow(
            "task-workspace-inherits", echo, workflowWorkspace("/workspace/shared"));

    // Absent on the node must reach createTaskList as null, not as false, or the opt-out below
    // would be the default.
    assertNull(
        savedNode(workflowId, "echo").getWorkspace(),
        "a node authored without a workspace flag must round-trip through Mongo as null");

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertMounted(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
        "/workspace/shared",
        "the materialised TaskRun should hold the run's workspace");

    String dispatcherId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "workspace-inheritance-zone",
                "workspace-inheritance-zone.local",
                List.of("template")));
    List<TaskRun> queue = dispatcherService.getTaskQueue(dispatcherId, null, null, null, null).getBody();
    assertNotNull(queue, "the dispatcher's queue should carry the claimed TaskRun");
    TaskRun claimed =
        queue.stream().filter(t -> taskRunId.equals(t.getId())).findFirst().orElse(null);
    assertNotNull(claimed, "the dispatcher's queue should carry the claimed TaskRun");
    assertMounted(
        claimed.getWorkspace(),
        "/workspace/shared",
        "the dispatcher's claim payload should carry the run's workspace");
  }

  @Test
  void aTaskSayingTrueMountsTheRunsWorkspace() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");
    echo.setWorkspace(Boolean.TRUE);

    String workflowId =
        createWorkflow("task-workspace-true", echo, workflowWorkspace("/workspace/shared"));

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertMounted(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
        "/workspace/shared",
        "a node saying workspace: true should mount the run's workspace");
  }

  @Test
  void aTaskSayingFalseMountsNothing() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");
    echo.setWorkspace(Boolean.FALSE);

    String workflowId =
        createWorkflow("task-workspace-opts-out", echo, workflowWorkspace("/workspace/shared"));

    // The opt-out only exists if false survives the save as false.
    assertEquals(
        Boolean.FALSE,
        savedNode(workflowId, "echo").getWorkspace(),
        "an explicit workspace: false must round-trip as false");

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertNull(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
        "a node saying workspace: false should mount nothing");
  }

  @Test
  void aWorkspaceNamingNoMountPathIsMountedAtTheDefault() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");

    // The shape every workflow created through the engine has: a size and nothing else.
    String workflowId =
        createWorkflow("task-workspace-default-mount", echo, workflowWorkspace(null));

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertMounted(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
        WorkflowWorkspace.DEFAULT_MOUNT_PATH,
        "a workspace naming no mountPath is settled to the default on the TaskRun");
  }

  @Test
  void aTaskOnARunWithNoWorkspaceMountsNothing() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");

    String workflowId = createWorkflow("task-workspace-none-anywhere", echo, null);

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertNull(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspace(),
        "nothing to mount should leave the TaskRun with no workspace");
  }

  /**
   * The YAML half of the same distinction: the API accepts a workflow as application/x-yaml, and
   * the rule needs an omitted {@code workspace:} key to arrive as null while an explicit {@code
   * workspace: false} arrives as false.
   */
  @Test
  void yamlDistinguishesAnOmittedWorkspaceKeyFromAnExplicitOptOut() {
    YAMLMapper mapper = new YAMLMapper();
    assertNull(
        mapper.readValue("name: echo\ntype: template\n", WorkflowTask.class).getWorkspace(),
        "an omitted workspace key must deserialise as null");
    assertEquals(
        Boolean.FALSE,
        mapper
            .readValue("name: echo\ntype: template\nworkspace: false\n", WorkflowTask.class)
            .getWorkspace(),
        "an explicit workspace: false must deserialise as false");
  }

  private String createWorkflow(String name, WorkflowTask echo, WorkflowWorkspace workspace) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setWorkspace(workspace);
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            echo,
            node("end", TaskType.end, null, "echo")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  // A run with a workspace parks at ready for dispatcher provisioning; either way the start call
  // is what walks the graph and materialises the TaskRuns.
  private String startAndAwaitEchoTaskRun(String workflowId) {
    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    return awaitEchoTaskRun(wfRunId);
  }

  private String awaitEchoTaskRun(String wfRunId) {
    awaitEngine("echo TaskRun claimable")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, taskRun.getStatus());
              assertEquals(RunPhase.pending, taskRun.getPhase());
            });
    return taskRunRepository.findFirstByNameAndWorkflowRunRef("echo", wfRunId).orElseThrow().getId();
  }

  private WorkflowTask savedNode(String workflowId, String name) {
    WorkflowRevisionEntity revision =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId).orElseThrow();
    return revision.getTasks().stream()
        .filter(t -> name.equals(t.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static void assertMounted(WorkflowWorkspace workspace, String mountPath, String message) {
    assertNotNull(workspace, message);
    assertEquals("1Gi", workspace.getSize(), message + " with the run's size");
    assertEquals(mountPath, workspace.getMountPath(), message + " at its settled mountPath");
  }

  private static WorkflowWorkspace workflowWorkspace(String mountPath) {
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("1Gi");
    workspace.setMountPath(mountPath);
    return workspace;
  }

  private static WorkflowTask node(String name, TaskType type, String taskRef, String dependsOn) {
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
