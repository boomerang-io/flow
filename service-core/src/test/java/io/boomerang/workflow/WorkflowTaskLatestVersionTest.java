package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.model.CanvasNode;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A workflow node with no {@code taskVersion} follows its task's latest version: the save checks it
 * against the latest and stores no version, a read returns it with none, and each run takes the
 * version that is latest when it starts. A node that names a version stays pinned to it.
 */
class WorkflowTaskLatestVersionTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-latest-version-ws";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, WORKSPACE)) {
      WorkspaceRequest request = new WorkspaceRequest();
      request.setName(WORKSPACE);
      request.setDisplayName(WORKSPACE);
      workspaceService.create(request);
    }
    setFeatureSetting("workspaceQuotas", false);
  }

  @Test
  void aLatestNodeSavesAndReadsWithNoVersion() {
    String task = taskWithParams("latest-save-task", param("greeting", "text"));

    workflowService.create(WORKSPACE, workflow("latest-save-wf", task, null));

    assertNull(storedNode("latest-save-wf").getTaskVersion(), "the save must not pin the node");
    assertNull(
        work(workflowService.get(WORKSPACE, "latest-save-wf", Optional.empty(), true))
            .getTaskVersion(),
        "a read must not fill the version in");
    CanvasNode canvasNode =
        workflowService.composeGet(WORKSPACE, "latest-save-wf", Optional.empty()).getNodes().stream()
            .filter(node -> "work".equals(node.getData().getName()))
            .findFirst()
            .orElseThrow();
    assertNull(canvasNode.getData().getTaskVersion(), "the editor must see the node as Latest");
  }

  @Test
  void aLatestNodeRunsTheVersionThatIsLatestWhenTheRunStarts() {
    String task = taskWithParams("latest-run-task", param("greeting", "text"));
    workflowService.create(WORKSPACE, workflow("latest-run-wf", task, null));
    publishNextVersion(task, List.of("echo", "v2"), param("greeting", "text"));

    TaskRunEntity work = runWork("latest-run-wf");

    assertEquals(2, work.getTaskVersion());
    assertEquals(List.of("echo", "v2"), work.getSpec().getCommand());
  }

  @Test
  void aPinnedNodeKeepsItsVersionOnSaveAndOnRun() {
    String task = taskWithParams("latest-pinned-task", param("greeting", "text"));
    workflowService.create(WORKSPACE, workflow("latest-pinned-wf", task, 1));
    publishNextVersion(task, List.of("echo", "v2"), param("greeting", "text"));

    assertEquals(1, storedNode("latest-pinned-wf").getTaskVersion());
    TaskRunEntity work = runWork("latest-pinned-wf");
    assertEquals(1, work.getTaskVersion());
    assertEquals(List.of("echo"), work.getSpec().getCommand());
  }

  @Test
  void aLatestNodeIsCheckedAgainstTheLatestVersionOnSave() {
    String task = taskWithParams("latest-check-task", param("greeting", "text"));
    publishNextVersion(
        task, List.of("echo"), param("greeting", "text"), param("registry", "text"));

    // The latest declares registry, so a Latest node may set it...
    workflowService.create(
        WORKSPACE, workflow("latest-check-wf", task, null, new RunParam("registry", "r")));
    // ...while a node pinned to version 1, which does not declare it, may not.
    BoomerangException pinned =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    WORKSPACE,
                    workflow("latest-check-pinned-wf", task, 1, new RunParam("registry", "r"))));
    assertEquals("WORKFLOW_INVALID_TASK_PARAM", pinned.getReason());
    BoomerangException undeclared =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    WORKSPACE,
                    workflow("latest-check-undeclared-wf", task, null, new RunParam("shout", "x"))));
    assertEquals("WORKFLOW_INVALID_TASK_PARAM", undeclared.getReason());
  }

  @Test
  void aLatestNodeReadsItsSecretsByTheLatestVersion() {
    String task = taskWithParams("latest-secret-task", param("token", "text"));
    workflowService.create(
        WORKSPACE, workflow("latest-secret-wf", task, null, new RunParam("token", "s3cret")));
    // A later version makes the param a secret: the node's value is hidden on every read.
    publishNextVersion(task, List.of("echo"), param("token", "password"));

    RunParam token =
        work(workflowService.get(WORKSPACE, "latest-secret-wf", Optional.empty(), true))
            .getParams()
            .get(0);
    assertEquals(DataAdapterUtil.REDACTED, token.getValue());
  }

  private TaskRunEntity runWork(String workflowName) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(WORKSPACE, workflowName, request, true);
    awaitEngine("the run's task runs to be created")
        .until(
            () ->
                taskRunRepository.findFirstByNameAndWorkflowRunRef("work", run.getId()).isPresent());
    return taskRunRepository.findFirstByNameAndWorkflowRunRef("work", run.getId()).orElseThrow();
  }

  private WorkflowTask storedNode(String workflowName) {
    String ref =
        relationshipService
            .refsInWorkspace(RelationshipType.WORKFLOW, WORKSPACE, Optional.of(List.of(workflowName)))
            .get(0);
    return workflowRevisionRepository.findByWorkflowRefAndLatestVersion(ref).orElseThrow().getTasks()
        .stream()
        .filter(t -> "work".equals(t.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowTask work(Workflow workflow) {
    return workflow.getTasks().stream()
        .filter(t -> "work".equals(t.getName()))
        .findFirst()
        .orElseThrow();
  }

  private Workflow workflow(String name, String taskSlug, Integer version, RunParam... params) {
    Workflow workflow = runnableWorkflow(name, taskSlug);
    WorkflowTask work = work(workflow);
    work.setTaskVersion(version);
    work.setParams(new LinkedList<>(List.of(params)));
    return workflow;
  }

  private String taskWithParams(String name, AbstractParam... params) {
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.TASK, name)) {
      Task task = new Task();
      task.setName(name);
      task.setType(TaskType.template);
      task.getSpec().setImage("busybox:latest");
      task.getSpec().setCommand(List.of("echo"));
      task.getSpec().setParams(new LinkedList<>(List.of(params)));
      taskService.createGlobal(task);
    }
    return name;
  }

  private void publishNextVersion(String name, List<String> command, AbstractParam... params) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(command);
    task.getSpec().setParams(new LinkedList<>(List.of(params)));
    taskService.applyGlobal(name, task, false);
  }

  private static AbstractParam param(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }
}
