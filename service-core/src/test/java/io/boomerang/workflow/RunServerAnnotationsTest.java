package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The run annotations the engine decides on - the owning workspace and the task defaults - are
 * the server's. A client's submit or start cannot set them, so a run never resolves another
 * workspace's parameters, and a retry is given them as they stand now.
 */
class RunServerAnnotationsTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "run-annotations-test-task";
  private static final String WORKSPACE_NAME = "boomerang.io/workspace-name";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting("workspaceQuotas", false);
    seedGlobalTask(TASK_SLUG);
  }

  @Test
  void aSubmitDropsTheServerAnnotationsAClientSends() {
    String workspace = createWorkspace("run-annotations-submit");
    String other = createWorkspace("run-annotations-submit-other");
    workflowService.create(workspace, workflow("run-annotations-submit-wf"));
    WorkflowSubmitRequest request = request();
    request.getAnnotations().put(WORKSPACE_NAME, other);
    request.getAnnotations().put("boomerang.io/task-timeout", "99999");
    request.getAnnotations().put("example.com/note", "kept");

    WorkflowRun run = workflowService.submit(workspace, "run-annotations-submit-wf", request, false);

    Map<String, Object> annotations = annotationsOf(run.getId());
    assertEquals(workspace, annotations.get(WORKSPACE_NAME));
    assertNotEquals("99999", annotations.get("boomerang.io/task-timeout"));
    assertEquals("kept", annotations.get("example.com/note"));
  }

  @Test
  void aStartCannotPointARunAtAnotherWorkspace() {
    String workspace = createWorkspace("run-annotations-start");
    String other = createWorkspace("run-annotations-start-other");
    workflowService.create(workspace, workflow("run-annotations-start-wf"));
    WorkflowRun run = workflowService.submit(workspace, "run-annotations-start-wf", request(), false);
    WorkflowRunRequest start = new WorkflowRunRequest();
    start.getAnnotations().put(WORKSPACE_NAME, other);
    start.getAnnotations().put("example.com/note", "kept");

    workflowRunService.start(run.getId(), Optional.of(start));

    Map<String, Object> annotations = annotationsOf(run.getId());
    assertEquals(workspace, annotations.get(WORKSPACE_NAME));
    assertEquals("kept", annotations.get("example.com/note"));
  }

  @Test
  void aRetryOfARunWithoutTheServerAnnotationsIsGivenThem() {
    String workspace = createWorkspace("run-annotations-retry");
    workflowService.create(workspace, workflow("run-annotations-retry-wf"));
    WorkflowRun origin = workflowService.submit(workspace, "run-annotations-retry-wf", request(), false);
    // An origin migrated from v3 carries none of them.
    WorkflowRunEntity migrated = workflowRunRepository.findById(origin.getId()).orElseThrow();
    migrated.getAnnotations().keySet().removeIf(key -> key.startsWith("boomerang.io/"));
    migrated.setStatus(RunStatus.failed);
    migrated.setPhase(RunPhase.completed);
    workflowRunRepository.save(migrated);

    WorkflowRun retried = workflowRunService.retry(workspace, origin.getId()).getBody();

    Map<String, Object> annotations = annotationsOf(retried.getId());
    assertEquals(workspace, annotations.get(WORKSPACE_NAME));
    assertNotNull(annotations.get("boomerang.io/task-default-image"));
    assertNotNull(annotations.get("boomerang.io/task-deletion"));
  }

  private Map<String, Object> annotationsOf(String runId) {
    return workflowRunRepository.findById(runId).orElseThrow().getAnnotations();
  }

  private static WorkflowSubmitRequest request() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private static Workflow workflow(String name) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                task("start", TaskType.start, null, null),
                task("work", TaskType.template, TASK_SLUG, "start"),
                task("end", TaskType.end, null, "work"))));
    return workflow;
  }

  private static WorkflowTask task(String name, TaskType type, String taskRef, String dependsOn) {
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
