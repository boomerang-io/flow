package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
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
 * A Task that declares no workspaces mounts every workspace its run carries - the canvas offers no
 * way to declare them, so that is the ordinary case. An empty declared list is the explicit
 * opt-out, and a declared list is honoured verbatim (pinned separately by TaskWorkspaceScopeTest).
 */
class TaskWorkspaceInheritanceTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  private String templateId;

  @BeforeEach
  void seedGraphRootAndTemplate() {
    seedRelationshipRoot();
    Task template = new Task();
    template.setName("workspace-inheritance-echo-" + System.nanoTime());
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    templateId = taskService.create(template).getId();
    assertNotNull(templateId);
  }

  @Test
  void aTaskDeclaringNoWorkspacesInheritsEveryWorkspaceOnTheRun() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");

    // The first workflow-level workspace carries a spec with a mountPath, the second carries no
    // spec at all - the shape every workflow created through the engine has.
    String workflowId =
        createWorkflow(
            "task-workspace-inherits-all",
            echo,
            List.of(
                workflowWorkspace("workflow", "workflow", "/workspace/shared"),
                workflowWorkspace("workflowrun", "workflowrun", null)));

    // Absent on the node must reach createTaskList as null, not as an empty list, or the opt-out
    // below is unreachable.
    assertNull(
        savedNode(workflowId, "echo").getWorkspaces(),
        "a node authored without workspaces must round-trip through Mongo as null");

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertInherited(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspaces(),
        "the materialised TaskRun should hold every workspace the run carries");

    String agentId =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "workspace-inheritance-zone",
                "workspace-inheritance-zone.local",
                List.of("template")));
    List<TaskRun> queue = dispatcherService.getTaskQueue(agentId).getBody();
    assertNotNull(queue, "the dispatcher's queue should carry the claimed TaskRun");
    TaskRun claimed =
        queue.stream().filter(t -> taskRunId.equals(t.getId())).findFirst().orElse(null);
    assertNotNull(claimed, "the dispatcher's queue should carry the claimed TaskRun");
    assertInherited(
        claimed.getWorkspaces(),
        "the dispatcher's claim payload should carry every workspace the run carries");
  }

  @Test
  void aTaskDeclaringAnEmptyWorkspaceListMountsNone() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");
    echo.setWorkspaces(new LinkedList<>());

    String workflowId =
        createWorkflow(
            "task-workspace-opts-out",
            echo,
            List.of(
                workflowWorkspace("workflow", "workflow", "/workspace/shared"),
                workflowWorkspace("workflowrun", "workflowrun", "/workspace/run")));

    // The opt-out only exists if an empty list survives the save as an empty list.
    List<TaskWorkspace> saved = savedNode(workflowId, "echo").getWorkspaces();
    assertNotNull(saved, "an explicitly empty workspace list must not round-trip as null");
    assertTrue(saved.isEmpty(), "an explicitly empty workspace list must stay empty");

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertTrue(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspaces().isEmpty(),
        "a node declaring an empty workspace list should mount none");
  }

  @Test
  void aTaskDeclaringNoWorkspacesOnARunWithNoneMountsNone() {
    WorkflowTask echo = node("echo", TaskType.template, templateId, "start");

    String workflowId = createWorkflow("task-workspace-none-anywhere", echo, null);

    String taskRunId = startAndAwaitEchoTaskRun(workflowId);

    assertTrue(
        taskRunRepository.findById(taskRunId).orElseThrow().getWorkspaces().isEmpty(),
        "nothing to inherit should leave the TaskRun with no workspaces");
  }

  /**
   * The YAML half of the same distinction: the API accepts a workflow as application/x-yaml, and
   * the three-way rule needs an omitted {@code workspaces:} key to arrive as null while an
   * explicit {@code workspaces: []} arrives as an empty list.
   */
  @Test
  void yamlDistinguishesAnOmittedWorkspacesKeyFromAnEmptyOne() {
    YAMLMapper mapper = new YAMLMapper();
    assertNull(
        mapper.readValue("name: echo\ntype: template\n", WorkflowTask.class).getWorkspaces(),
        "an omitted workspaces key must deserialise as null");
    List<TaskWorkspace> empty =
        mapper
            .readValue("name: echo\ntype: template\nworkspaces: []\n", WorkflowTask.class)
            .getWorkspaces();
    assertNotNull(empty, "an explicit empty workspaces list must not deserialise as null");
    assertTrue(empty.isEmpty(), "an explicit empty workspaces list must deserialise as empty");
  }

  private String createWorkflow(
      String name, WorkflowTask echo, List<WorkflowWorkspace> workspaces) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    if (workspaces != null) {
      workflow.setWorkspaces(new LinkedList<>(workspaces));
    }
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            echo,
            node("end", TaskType.end, null, "echo")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  // A run with workspaces parks at ready for dispatcher provisioning (decision 0067); either way
  // the start call is what walks the graph and materialises the TaskRuns.
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

  private static void assertInherited(List<TaskWorkspace> workspaces, String message) {
    assertNotNull(workspaces, message);
    assertEquals(
        List.of("workflow", "workflowrun"),
        workspaces.stream().map(TaskWorkspace::getName).toList(),
        message + " in the run's own order");
    assertEquals(
        "/workspace/shared",
        workspaces.get(0).getMountPath(),
        "an inherited workspace takes its mountPath from the workflow-level spec");
    assertNull(
        workspaces.get(1).getMountPath(),
        "a workspace with no spec leaves mountPath null for the executor's fallback");
    assertEquals("workflow", workspaces.get(0).getType());
    assertEquals("workflowrun", workspaces.get(1).getType());
  }

  private static WorkflowWorkspace workflowWorkspace(String name, String type, String mountPath) {
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setName(name);
    workspace.setType(type);
    if (mountPath != null) {
      WorkflowWorkspaceSpec spec = new WorkflowWorkspaceSpec();
      spec.setMountPath(mountPath);
      spec.setSize("1Gi");
      workspace.setSpec(spec);
    }
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
