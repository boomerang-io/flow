package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TaskDeletion;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A run's workspace is its workflow's owner in the relationship graph, and a TaskRun takes the
 * task settings when its task sets none - nothing the engine decides on is read from, or written
 * to, a run annotation. The {@code boomerang.io/} annotation prefix stays reserved for the server,
 * which writes only a run's {@code generation} and {@code kind} markers there.
 */
class RunWorkspaceAndTaskDefaultsTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "run-defaults-test-task";
  private static final String IMAGELESS_TASK_SLUG = "run-defaults-imageless-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ParamLayerService paramLayerService;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", true);
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.TASK, IMAGELESS_TASK_SLUG)) {
      Task task = new Task();
      task.setName(IMAGELESS_TASK_SLUG);
      task.setType(TaskType.template);
      task.getSpec().setCommand(List.of("echo"));
      taskService.createGlobal(task);
    }
  }

  @Test
  void aSubmitAndAStartStampNoEngineAnnotationsAndDropAClientsOnesAndItsServerLabels() {
    Workspace workspace = createWorkspace("run-defaults-submit");
    createWorkspace("run-defaults-submit-other");
    workflowService.create(workspace.getName(), runnableWorkflow("run-defaults-submit-wf", TASK_SLUG));
    WorkflowSubmitRequest request = request();
    request.getAnnotations().put("boomerang.io/workspace-name", "run-defaults-submit-other");
    request.getAnnotations().put("boomerang.io/task-timeout", "99999");
    request.getAnnotations().put("example.com/note", "kept");
    request.getLabels().put("boomerang.io/item", "forged");
    request.getLabels().put("team", "kept");

    WorkflowRun run =
        workflowService.submit(workspace.getName(), "run-defaults-submit-wf", request, false);
    WorkflowRunRequest start = new WorkflowRunRequest();
    start.getAnnotations().put("boomerang.io/workspace-name", "run-defaults-submit-other");
    start.getAnnotations().put("example.com/started", "kept");
    start.getLabels().put("boomerang.io/model", "forged");
    workflowRunService.start(run.getId(), Optional.of(start));

    WorkflowRunEntity stored = workflowRunRepository.findById(run.getId()).orElseThrow();
    // A label under the server's prefix is the server's alone: a client's is dropped.
    assertEquals(Map.of("team", "kept"), stored.getLabels());
    Map<String, Object> annotations = stored.getAnnotations();
    // Only the run's generation and kind markers, which nothing decides from, are the server's.
    assertEquals(
        Set.of("boomerang.io/generation", "boomerang.io/kind"),
        annotations.keySet().stream()
            .filter(key -> key.startsWith("boomerang.io/"))
            .collect(Collectors.toSet()),
        "no engine annotation is stamped and a client's are dropped; got " + annotations);
    assertEquals("kept", annotations.get("example.com/note"));
    assertEquals("kept", annotations.get("example.com/started"));
  }

  @Test
  void aRunFindsItsWorkspaceByTheWorkflowsOwnerAfterARename() {
    AbstractParam region = new AbstractParam();
    region.setName("region");
    region.setType("text");
    region.setValue("ap-southeast-2");
    Workspace workspace = createWorkspace("run-defaults-rename", region);
    workflowService.create(workspace.getName(), runnableWorkflow("run-defaults-rename-wf", TASK_SLUG));
    WorkflowRun run =
        workflowService.submit(workspace.getName(), "run-defaults-rename-wf", request(), false);
    WorkflowRunEntity entity = workflowRunRepository.findById(run.getId()).orElseThrow();

    WorkspaceRequest rename = new WorkspaceRequest();
    rename.setName("run-defaults-renamed");
    workspaceService.patch(workspace.getName(), rename);

    assertEquals(workspace.getId(), paramLayerService.owningWorkspace(entity.getWorkflowRef()));
    assertEquals(
        "ap-southeast-2",
        paramLayerService
            .buildParamLayers(
                entity.getWorkflowRef(),
                paramLayerService.getRevision(entity.getWorkflowRevisionRef()))
            .getWorkspaceParams()
            .get("region"),
        "the renamed workspace's parameters must still resolve");
  }

  @Test
  void aTaskThatNamesNoImageTakesTheTaskSettings() {
    Workspace workspace = createWorkspace("run-defaults-image");
    workflowService.create(
        workspace.getName(), runnableWorkflow("run-defaults-image-wf", IMAGELESS_TASK_SLUG));

    WorkflowRun run =
        workflowService.submit(workspace.getName(), "run-defaults-image-wf", request(), true);

    awaitEngine("the run's TaskRuns to be created at queue")
        .until(
            () ->
                taskRunRepository.findFirstByNameAndWorkflowRunRef("work", run.getId()).isPresent());
    TaskRunEntity work =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", run.getId()).orElseThrow();
    assertEquals(taskSetting("default.image"), work.getSpec().getImage());
    assertEquals(
        TaskDeletion.getDeletion(taskSetting("deletion.policy")), work.getSpec().getDeletion());
    assertTrue(
        work.getAnnotations() == null
            || !work.getAnnotations().containsKey("boomerang.io/workspace-name"),
        "a TaskRun carries no workspace name; got " + work.getAnnotations());
  }

  private String taskSetting(String key) {
    return settingsRepository.findOneByKey("task").getConfig().stream()
        .filter(config -> key.equals(config.getKey()))
        .map(SettingConfig::getValue)
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowSubmitRequest request() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private Workspace createWorkspace(String name, AbstractParam... parameters) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setParameters(new LinkedList<>(List.of(parameters)));
    return workspaceService.create(request);
  }
}
