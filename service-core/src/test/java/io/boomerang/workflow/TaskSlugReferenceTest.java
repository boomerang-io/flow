package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.security.IdentityService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * A task reference is stored as the task's id and read as its slug form: {@code <slug>} for a
 * global task, {@code <workspace>/<slug>} for a workspace task. A workflow saved with a slug form
 * stores ids; a run read, its insight rows and a mixed batch translate back in a fixed number of
 * reads.
 */
class TaskSlugReferenceTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRunInsightService insightService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private TaskRepository taskRepository;
  @Autowired private TaskRevisionRepository taskRevisionRepository;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private IdentityService identityService;
  @Autowired private UserService userService;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final String globalSlug = "slug-global-" + tag;
  private final String localSlug = "slug-local-" + tag;
  private String workspace;

  @BeforeEach
  void seed() {
    setFeatureSetting("workspaceQuotas", false);
    workspace = workspace("slug-ws-" + tag);
    taskService.createGlobal(task(globalSlug));
    taskService.create(workspace, task(localSlug));
  }

  @Test
  void aWorkflowSavedWithSlugFormsStoresIdsAndReadsBackSlugForms() {
    Workflow created = workflowService.create(workspace, workflow("slug-read-" + tag));

    Map<String, WorkflowTask> nodes = byName(created.getTasks(), WorkflowTask::getName);
    assertEquals(globalSlug, nodes.get("g").getTaskRef());
    assertEquals(workspace + "/" + localSlug, nodes.get("w").getTaskRef());
  }

  @Test
  void aRunReadShowsEachTaskInItsSlugFormWhileStorageKeepsTheId() {
    String runId = submitted("slug-run-" + tag);

    WorkflowRun run = workflowRunService.get(workspace, runId, true).getBody();

    Map<String, TaskRun> tasks = byName(run.getTasks(), TaskRun::getName);
    assertEquals(
        List.of(globalSlug, workspace + "/" + localSlug),
        List.of(tasks.get("g").getTaskRef(), tasks.get("w").getTaskRef()));
    Map<String, TaskRunEntity> stored =
        byName(taskRunRepository.findByWorkflowRunRefAndParentRefIsNull(runId), TaskRunEntity::getName);
    assertEquals(globalId(), stored.get("g").getTaskRef());
    assertEquals(localId(), stored.get("w").getTaskRef());
  }

  @Test
  void insightRowsShowEachTaskInItsSlugForm() {
    String name = "slug-insight-" + tag;
    submitted(name);

    WorkflowRunInsightWorkflowDetail detail =
        insightService.workflowDetail(
            workspace,
            name,
            new Date(System.currentTimeMillis() - 3_600_000L),
            new Date(System.currentTimeMillis() + 60_000L),
            Optional.empty());

    Map<String, WorkflowRunInsightWorkflowDetail.TaskRow> rows =
        byName(detail.getTasks(), WorkflowRunInsightWorkflowDetail.TaskRow::getName);
    assertEquals(globalSlug, rows.get("g").getTaskRef());
    assertEquals(workspace + "/" + localSlug, rows.get("w").getTaskRef());
  }

  @Test
  void aWorkflowNamingAnotherWorkspacesTaskIsRefused() {
    String other = workspace("slug-other-" + tag);
    String foreign = "slug-foreign-" + tag;
    taskService.create(other, task(foreign));
    Workflow workflow = workflow("slug-foreign-" + tag);
    workflow.getTasks().get(2).setTaskRef(other + "/" + foreign);

    BoomerangException refused =
        assertThrows(BoomerangException.class, () -> workflowService.create(workspace, workflow));

    assertEquals("WORKFLOW_INVALID_TASK_REF", refused.getReason());
  }

  @Test
  void applyingAGlobalTaskByNameUpdatesTheExistingTask() {
    Task update = task(globalSlug);
    update.getSpec().setImage("busybox:1.36");

    taskService.applyGlobal(globalSlug, update, false);

    List<String> refs =
        relationshipService.filter(
            RelationshipType.TASK, Optional.of(List.of(globalSlug)), Optional.empty(), Optional.empty(), false);
    assertEquals(1, refs.size());
    assertEquals(2, taskService.get(refs.get(0), Optional.empty()).getVersion());
    assertEquals("busybox:1.36", taskService.get(refs.get(0), Optional.empty()).getSpec().getImage());
  }

  @Test
  void aMixedBatchCostsOneNodeReadAndOneParentReadAndIsCachedAfter() {
    RelationshipService relationships = spy(relationshipService);
    TaskService fresh =
        new TaskService(
            taskRepository,
            taskRevisionRepository,
            taskRunRepository,
            mongoTemplate,
            relationships,
            identityService,
            userService);

    Map<String, String> first = fresh.slugsByRef(List.of(globalId(), localId()));
    Map<String, String> second = fresh.slugsByRef(List.of(globalId(), localId()));

    assertEquals(Map.of(globalId(), globalSlug, localId(), workspace + "/" + localSlug), first);
    assertEquals(first, second);
    // One node read for both kinds of task, one parent read (edges, then nodes) for the workspace;
    // the second batch is served from the cache.
    verify(relationships, times(1)).nodesByRef(any(Collection.class), any(RelationshipType[].class));
    verify(relationships, times(1)).parentsByLabel(any(RelationshipLabel.class), any(Collection.class));
  }

  private String submitted(String name) {
    workflowService.create(workspace, workflow(name));
    return workflowService.submit(workspace, name, new WorkflowSubmitRequest(), false).getId();
  }

  private String globalId() {
    return relationshipService
        .filter(RelationshipType.TASK, Optional.of(List.of(globalSlug)), Optional.empty(), Optional.empty(), false)
        .get(0);
  }

  private String localId() {
    return relationshipService
        .filter(
            RelationshipType.WORKSPACETASK,
            Optional.of(List.of(localSlug)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)),
            false)
        .get(0);
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  // start -> g (a global task) -> w (this workspace's task) -> end
  private Workflow workflow(String name) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                node("start", TaskType.start, null, null),
                node("g", TaskType.template, globalSlug, "start"),
                node("w", TaskType.template, workspace + "/" + localSlug, "g"),
                node("end", TaskType.end, null, "w"))));
    return workflow;
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

  private static Task task(String name) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    return task;
  }

  private static <T> Map<String, T> byName(List<T> items, Function<T, String> name) {
    return items.stream().collect(Collectors.toMap(name, Function.identity(), (a, b) -> a));
  }
}
