package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A task poll narrowed by task slug or by a label on the workflow definition claims only the
 * matching work, hands out termination orders for only that work, and claims nothing - at once -
 * when its filter matches nothing. A slug is a global task's, or {@code <workspace>/<slug>} a
 * workspace task's, and the task runs handed out carry their task in that form.
 */
class DispatcherClaimFilterTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private TaskRepository taskRepository;
  @Autowired private WorkflowRepository workflowRepository;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final List<String> taskIds = new ArrayList<>();
  private final List<String> workflowIds = new ArrayList<>();
  private final List<String> taskRunIds = new ArrayList<>();
  private final List<String> workspaces = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    taskRunIds.forEach(taskRunRepository::deleteById);
    taskIds.forEach(
        id -> {
          relationshipService.removeNodeAndEdgeByRef(RelationshipType.TASK, id);
          relationshipService.removeNodeAndEdgeByRef(RelationshipType.WORKSPACETASK, id);
          taskRepository.deleteById(id);
        });
    workspaces.forEach(
        ws -> relationshipService.removeNodeAndEdgeByRef(RelationshipType.WORKSPACE, ws));
    workflowIds.forEach(workflowRepository::deleteById);
  }

  private String dispatcher() {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(
            "filter-" + tag, "filter-" + tag + ".local", List.of("template")));
  }

  // A global task: its node carries the slug, as createGlobal writes it.
  private String task(String slug) {
    String id = savedTask(slug);
    relationshipService.createNode(RelationshipType.TASK, id, slug, Optional.empty());
    return id;
  }

  // A task of one workspace, reached from the workspace's node, as TaskService.create writes it.
  private String workspaceTask(String workspace, String slug) {
    if (!workspaces.contains(workspace)) {
      relationshipService.createNode(RelationshipType.WORKSPACE, workspace, workspace, Optional.empty());
      workspaces.add(workspace);
    }
    String id = savedTask(slug);
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_TASK,
        RelationshipType.WORKSPACETASK,
        id,
        slug,
        Optional.empty(),
        Optional.empty());
    return id;
  }

  private String savedTask(String slug) {
    TaskEntity task = new TaskEntity();
    task.setName(slug);
    task.setType(TaskType.template);
    String id = taskRepository.save(task).getId();
    taskIds.add(id);
    return id;
  }

  private static List<String> taskRefs(ResponseEntity<List<TaskRun>> response) {
    return (response.getBody() == null)
        ? List.of()
        : response.getBody().stream().map(TaskRun::getTaskRef).toList();
  }

  private String workflow(String workload) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName("filter-" + tag + "-" + workload);
    workflow.setStatus(WorkflowStatus.active);
    workflow.getLabels().put("archie.io/workload", workload);
    String id = workflowRepository.save(workflow).getId();
    workflowIds.add(id);
    return id;
  }

  private String run(String taskRef, String workflowRef, RunStatus status, RunPhase phase) {
    TaskRunEntity run =
        savedTaskRun(
            "filter-" + UUID.randomUUID(),
            TaskType.template,
            status,
            phase,
            workflowRef,
            "filter-run-" + tag);
    run.setTaskRef(taskRef);
    taskRunRepository.save(run);
    taskRunIds.add(run.getId());
    return run.getId();
  }

  // A cancelled run a dispatcher still owns: what a termination order is made of.
  private String ownedCancelledRun(String taskRef, String owner) {
    String id = run(taskRef, null, RunStatus.cancelled, RunPhase.completed);
    TaskRunEntity run = taskRunRepository.findById(id).orElseThrow();
    RunClaim claim = new RunClaim();
    claim.setBy(owner);
    claim.setAt(new Date());
    claim.setSeq(1L);
    run.setClaim(claim);
    taskRunRepository.save(run);
    return id;
  }

  private static List<String> ids(ResponseEntity<List<TaskRun>> response) {
    return (response.getBody() == null)
        ? List.of()
        : response.getBody().stream().map(TaskRun::getId).toList();
  }

  private boolean claimed(String taskRunId) {
    TaskRunEntity run = taskRunRepository.findById(taskRunId).orElseThrow();
    return run.getClaim() != null && run.getClaim().getBy() != null;
  }

  @Test
  void aTaskFilterClaimsOnlyThatTasksRuns() {
    String agent = run(task("filter-" + tag + "-agent"), null, RunStatus.ready, RunPhase.pending);
    String other = run(task("filter-" + tag + "-other"), null, RunStatus.ready, RunPhase.pending);

    List<String> delivered =
        ids(
            dispatcherService.getTaskQueue(
                dispatcher(), null, null, "filter-" + tag + "-ag*", null));

    assertThat(delivered).containsExactly(agent);
    assertThat(claimed(other)).isFalse();
  }

  @Test
  void aWorkflowLabelFilterClaimsOnlyRunsOfLabelledWorkflows() {
    String taskRef = task("filter-" + tag + "-shared");
    String knowledge =
        run(taskRef, workflow("knowledge-" + tag), RunStatus.ready, RunPhase.pending);
    String analysis = run(taskRef, workflow("analysis-" + tag), RunStatus.ready, RunPhase.pending);

    List<String> delivered =
        ids(
            dispatcherService.getTaskQueue(
                dispatcher(), null, null, null, "archie.io/workload=knowledge-" + tag));

    assertThat(delivered).containsExactly(knowledge);
    assertThat(claimed(analysis)).isFalse();
  }

  @Test
  void aFilterThatMatchesNothingClaimsNothingAndAnswersAtOnce() {
    String ready = run(task("filter-" + tag + "-ready"), null, RunStatus.ready, RunPhase.pending);

    long started = System.currentTimeMillis();
    ResponseEntity<List<TaskRun>> response =
        dispatcherService.getTaskQueue(dispatcher(), null, null, "no-such-task-" + tag, null);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    assertThat(System.currentTimeMillis() - started).isLessThan(5000L);
    assertThat(claimed(ready)).isFalse();
  }

  @Test
  void terminationOrdersFollowTheFilter() {
    String owner = dispatcher();
    String mine = ownedCancelledRun(task("filter-" + tag + "-mine"), owner);
    String theirs = ownedCancelledRun(task("filter-" + tag + "-theirs"), owner);

    List<String> delivered =
        ids(dispatcherService.getTaskQueue(owner, 0, null, "filter-" + tag + "-mine", null));

    assertThat(delivered).containsExactly(mine);
    assertThat(claimed(theirs)).isTrue();
  }

  @Test
  void aWorkspaceSlugClaimsOnlyThatWorkspacesTaskAndABareSlugOnlyTheGlobalOne() {
    String slug = "filter-" + tag + "-build";
    String workspace = "filter-ws-" + tag;
    String global = run(task(slug), null, RunStatus.ready, RunPhase.pending);
    String local = run(workspaceTask(workspace, slug), null, RunStatus.ready, RunPhase.pending);
    String dispatcher = dispatcher();

    ResponseEntity<List<TaskRun>> scoped =
        dispatcherService.getTaskQueue(dispatcher, null, null, workspace + "/" + slug, null);

    assertThat(ids(scoped)).containsExactly(local);
    assertThat(taskRefs(scoped)).containsExactly(workspace + "/" + slug);
    assertThat(claimed(global)).isFalse();

    ResponseEntity<List<TaskRun>> bare =
        dispatcherService.getTaskQueue(dispatcher, null, null, slug, null);

    assertThat(ids(bare)).containsExactly(global);
    assertThat(taskRefs(bare)).containsExactly(slug);
  }

  @Test
  void aWildcardAppliesToTheSlugHalfOfAWorkspaceSlug() {
    String workspace = "filter-ws-" + tag;
    String deploy =
        run(workspaceTask(workspace, "filter-" + tag + "-deploy"), null, RunStatus.ready, RunPhase.pending);
    String test =
        run(workspaceTask(workspace, "filter-" + tag + "-test"), null, RunStatus.ready, RunPhase.pending);

    List<String> delivered =
        ids(
            dispatcherService.getTaskQueue(
                dispatcher(), null, null, workspace + "/filter-" + tag + "-dep*", null));

    assertThat(delivered).containsExactly(deploy);
    assertThat(claimed(test)).isFalse();
  }

  @Test
  void theStartAndEndAnswersCarryTheTaskInItsSlugForm() {
    String workspace = "filter-ws-" + tag;
    String slug = "filter-" + tag + "-answer";
    String taskRunId =
        run(workspaceTask(workspace, slug), null, RunStatus.ready, RunPhase.pending);
    String dispatcher = dispatcher();
    assertThat(ids(dispatcherService.getTaskQueue(dispatcher, null, null, workspace + "/" + slug, null)))
        .containsExactly(taskRunId);

    TaskRunStartRequest start = new TaskRunStartRequest();
    start.setDispatcherRef(dispatcher);
    TaskRun started = dispatcherService.startTaskRun(taskRunId, Optional.of(start)).getBody();

    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.setDispatcherRef(dispatcher);
    TaskRun ended = dispatcherService.endTaskRun(taskRunId, Optional.of(end)).getBody();

    assertEquals(workspace + "/" + slug, started.getTaskRef());
    assertEquals(workspace + "/" + slug, ended.getTaskRef());
    // Storage keeps the id; only the answer carries the slug form.
    assertEquals(taskIds.get(0), taskRunRepository.findById(taskRunId).orElseThrow().getTaskRef());
  }

  @Test
  void aTypeTheDispatcherDidNotRegisterIsRejected() {
    BoomerangException rejected =
        assertThrows(
            BoomerangException.class,
            () -> dispatcherService.getTaskQueue(dispatcher(), null, "script", null, null));

    assertEquals(BoomerangError.QUERY_INVALID_FILTERS.getCode(), rejected.getCode());
  }
}
