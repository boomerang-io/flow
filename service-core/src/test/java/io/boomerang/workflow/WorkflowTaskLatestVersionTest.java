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
import io.boomerang.workflow.model.CanvasNodePosition;
import io.boomerang.workflow.model.WorkflowCanvas;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * A workflow node's {@code taskVersion} on the wire is a number, {@code "latest"}, or left out. A
 * number pins the node. Left out, the save pins the version that is latest then, as it always has.
 * {@code "latest"} is stored as no version, reads back as {@code "latest"}, and each run takes the
 * version that is latest when it starts. Anything else is refused.
 */
class WorkflowTaskLatestVersionTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-latest-version-ws";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ObjectMapper objectMapper;

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
  void aNodeThatNamesNoVersionIsPinnedToTheLatestOnSave() {
    String task = taskWithParams("latest-omitted-task", param("greeting", "text"));
    publishNextVersion(task, List.of("echo", "v2"), param("greeting", "text"));

    workflowService.create(WORKSPACE, fromJson("latest-omitted-wf", task, ""));

    assertEquals(2, storedNode("latest-omitted-wf").getTaskVersion());
    assertEquals(2, readNode("latest-omitted-wf").versionOnWire());
  }

  @Test
  void aLatestNodeIsStoredWithNoVersionAndReadsBackAsLatest() {
    String task = taskWithParams("latest-save-task", param("greeting", "text"));

    workflowService.create(
        WORKSPACE, fromJson("latest-save-wf", task, ",\"taskVersion\":\"latest\""));

    assertNull(storedNode("latest-save-wf").getTaskVersion(), "the save must not pin the node");
    assertEquals("latest", readNode("latest-save-wf").versionOnWire());
    assertEquals(
        "latest",
        objectMapper
            .valueToTree(workflowService.get(WORKSPACE, "latest-save-wf", Optional.empty(), true))
            .get("tasks")
            .get(1)
            .get("taskVersion")
            .asString());
    assertEquals("latest", canvasNode("latest-save-wf").getData().getTaskVersion());
  }

  @Test
  void aLatestNodeSavedThroughTheCanvasStaysLatestAndAnOmittedOneIsPinned() {
    String task = taskWithParams("latest-canvas-task", param("greeting", "text"));
    workflowService.create(WORKSPACE, workflow("latest-canvas-wf", task, 1));
    WorkflowCanvas canvas = workflowService.composeGet(WORKSPACE, "latest-canvas-wf", Optional.empty());
    // The editor sends every node's position; a workflow saved through the API has none.
    canvas.getNodes().forEach(node -> node.setPosition(new CanvasNodePosition()));
    work(canvas).getData().setTaskVersion("latest");

    workflowService.composeApply(WORKSPACE, canvas, false);
    assertNull(storedNode("latest-canvas-wf").getTaskVersion());

    work(canvas).getData().setTaskVersion(null);
    workflowService.composeApply(WORKSPACE, canvas, false);
    assertEquals(1, storedNode("latest-canvas-wf").getTaskVersion());
  }

  @Test
  void aLatestNodeRunsTheVersionThatIsLatestWhenTheRunStarts() {
    String task = taskWithParams("latest-run-task", param("greeting", "text"));
    workflowService.create(WORKSPACE, workflow("latest-run-wf", task, "latest"));
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
  void aVersionThatIsNeitherANumberNorLatestIsRefused() {
    String task = taskWithParams("latest-invalid-task", param("greeting", "text"));

    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () ->
                workflowService.create(
                    WORKSPACE,
                    fromJson("latest-invalid-wf", task, ",\"taskVersion\":\"newest\"")));

    assertEquals("WORKFLOW_INVALID_TASK_REF", refused.getReason());
    assertEquals(400, refused.getStatus().value());
    assertEquals(List.of("work", task + "@newest"), List.of(refused.getArgs()));
  }

  @Test
  void aLatestNodeIsCheckedAgainstTheLatestVersionOnSave() {
    String task = taskWithParams("latest-check-task", param("greeting", "text"));
    publishNextVersion(
        task, List.of("echo"), param("greeting", "text"), param("registry", "text"));

    // The latest declares registry, so a Latest node may set it...
    workflowService.create(
        WORKSPACE, workflow("latest-check-wf", task, "latest", new RunParam("registry", "r")));
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
                    workflow(
                        "latest-check-undeclared-wf", task, "latest", new RunParam("shout", "x"))));
    assertEquals("WORKFLOW_INVALID_TASK_PARAM", undeclared.getReason());
  }

  @Test
  void aDuplicateOfALatestNodeStaysLatest() {
    String task = taskWithParams("latest-duplicate-task", param("greeting", "text"));
    workflowService.create(WORKSPACE, workflow("latest-duplicate-wf", task, "latest"));

    workflowService.duplicate(WORKSPACE, "latest-duplicate-wf");

    assertNull(storedNode("latest-duplicate-wf-duplicate").getTaskVersion());
  }

  @Test
  void aLatestNodeReadsItsSecretsByTheLatestVersion() {
    String task = taskWithParams("latest-secret-task", param("token", "text"));
    workflowService.create(
        WORKSPACE,
        workflow("latest-secret-wf", task, "latest", new RunParam("token", "s3cret")));
    // A later version makes the param a secret: the node's value is hidden on every read.
    publishNextVersion(task, List.of("echo"), param("token", "password"));

    assertEquals(DataAdapterUtil.REDACTED, readNode("latest-secret-wf").getParams().get(0).getValue());
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
    return work(workflowRevisionRepository.findByWorkflowRefAndLatestVersion(ref).orElseThrow().getTasks());
  }

  private WorkflowTask readNode(String workflowName) {
    return work(workflowService.get(WORKSPACE, workflowName, Optional.empty(), true).getTasks());
  }

  private CanvasNode canvasNode(String workflowName) {
    return work(workflowService.composeGet(WORKSPACE, workflowName, Optional.empty()));
  }

  private static CanvasNode work(WorkflowCanvas canvas) {
    return canvas.getNodes().stream()
        .filter(node -> "work".equals(node.getData().getName()))
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowTask work(List<WorkflowTask> tasks) {
    return tasks.stream().filter(t -> "work".equals(t.getName())).findFirst().orElseThrow();
  }

  /** start -> work -> end, the work node's version given as the wire would give it. */
  private Workflow workflow(String name, String taskSlug, Object version, RunParam... params) {
    Workflow workflow = runnableWorkflow(name, taskSlug);
    WorkflowTask work = work(workflow.getTasks());
    work.requestVersion(version);
    work.setParams(new LinkedList<>(List.of(params)));
    return workflow;
  }

  /** The same workflow read by the application's own mapper, the work node carrying {@code version}. */
  private Workflow fromJson(String name, String taskSlug, String version) {
    String json =
        """
        {"name":"%s","tasks":[
          {"name":"start","type":"start"},
          {"name":"work","type":"template","taskRef":"%s"%s,"dependencies":[{"taskRef":"start"}]},
          {"name":"end","type":"end","dependencies":[{"taskRef":"work"}]}]}
        """
            .formatted(name, taskSlug, version);
    return objectMapper.readValue(json, Workflow.class);
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
