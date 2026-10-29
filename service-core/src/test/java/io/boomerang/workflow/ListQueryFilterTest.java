package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.User;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Workspace;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Every list route reads its filters the same way: a label is split once on its first {@code =},
 * so a value may hold one; a label without a value is a {@code 400 QUERY_INVALID_FILTERS}; the user
 * list's {@code ids} filter matches; the action counts exclude their end instant.
 */
class ListQueryFilterTest extends AbstractEngineIntegrationTest {

  private static final Optional<List<String>> NO_LABEL_VALUE = Optional.of(List.of("team"));

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private UserService userService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowTemplateService workflowTemplateService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private ActionService actionService;

  private String suffix;
  // A dotted key, so the stored form ("#" for ".") is exercised on every route.
  private String key;
  private Optional<List<String>> valueWithAnEquals;

  @BeforeEach
  void uniqueLabel() {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    key = "q.k" + suffix;
    valueWithAnEquals = Optional.of(List.of(key + "%3Db%3Dc"));
  }

  @Test
  void users() {
    UserEntity match = user("b=c");
    user("b");

    assertThat(
            userService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    valueWithAnEquals,
                    Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(User::getId)
        .containsExactly(match.getId());
    assertRefused(
        () ->
            userService.query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NO_LABEL_VALUE,
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void usersByIds() {
    UserEntity first = user("x");
    UserEntity second = user("x");
    user("x");

    assertThat(
            userService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of(first.getId(), second.getId())))
                .getContent())
        .extracting(User::getId)
        .containsExactlyInAnyOrder(first.getId(), second.getId());
  }

  @Test
  void workspaces() {
    WorkspaceEntity match = workspace("b=c");
    workspace("b");

    assertThat(
            workspaceService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    valueWithAnEquals,
                    Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(Workspace::getName)
        .containsExactly(match.getName());
    assertRefused(
        () ->
            workspaceService.query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NO_LABEL_VALUE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void workflows() {
    WorkflowEntity match = workflow("b=c");
    workflow("b");

    assertThat(
            workflowService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    valueWithAnEquals,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(Workflow::getId)
        .containsExactly(match.getId());
    assertRefused(
        () ->
            workflowService.query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NO_LABEL_VALUE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void tasks() {
    TaskEntity match = task("b=c");
    task("b");

    assertThat(
            taskService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    valueWithAnEquals,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(Task::getId)
        .containsExactly(match.getId());
    assertRefused(
        () ->
            taskService.query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NO_LABEL_VALUE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void templates() {
    WorkflowEntity match = starter("b=c");
    starter("b");

    assertThat(
            workflowTemplateService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    valueWithAnEquals,
                    Optional.empty(),
                    Optional.empty())
                .getContent())
        .extracting(Workflow::getName)
        .containsExactly(match.getName());
    assertRefused(
        () ->
            workflowTemplateService.query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NO_LABEL_VALUE,
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void runs() {
    String workflowRef = "list-filter-wf-" + suffix;
    WorkflowRunEntity match = run(workflowRef, "b=c");
    run(workflowRef, "b");

    assertThat(
            workflowRunService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    valueWithAnEquals,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of(workflowRef)),
                    Optional.empty())
                .getContent())
        .extracting(WorkflowRun::getId)
        .containsExactly(match.getId());
    assertRefused(
        () ->
            workflowRunService.query(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NO_LABEL_VALUE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(List.of(workflowRef)),
                Optional.empty()));
  }

  @Test
  void runStatusesAreReadIgnoringCase() {
    String workflowRef = "list-filter-wf-" + suffix;
    WorkflowRunEntity succeeded = run(workflowRef, "x");
    succeeded.setStatus(RunStatus.succeeded);
    workflowRunRepository.save(succeeded);
    run(workflowRef, "x");

    assertThat(
            workflowRunService
                .query(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of("SUCCEEDED")),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(List.of(workflowRef)),
                    Optional.empty())
                .getContent())
        .extracting(WorkflowRun::getId)
        .containsExactly(succeeded.getId());
  }

  @Test
  void actionCountsExcludeTheirEndInstant() {
    String workspaceName = "list-filter-ws-" + suffix;
    String workflowRef = "list-filter-wf-" + suffix;
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        workspaceName,
        workspaceName,
        Optional.empty(),
        Optional.empty());
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspaceName,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowRef,
        workflowRef,
        Optional.empty(),
        Optional.empty());
    Date earlier = new Date(1_700_000_000_000L);
    Date end = new Date(earlier.getTime() + 60_000L);
    approval(workflowRef, earlier);
    approval(workflowRef, end);

    assertThat(
            actionService
                .summary(
                    workspaceName,
                    Optional.of(new Date(earlier.getTime() - 1)),
                    Optional.of(end),
                    Optional.empty(),
                    Optional.empty())
                .getApprovals())
        .isEqualTo(1L);
  }

  private static void assertRefused(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getCode())
        .isEqualTo(BoomerangError.QUERY_INVALID_FILTERS.getCode());
  }

  private Map<String, String> labelled(String value) {
    return Map.of(key, value);
  }

  private UserEntity user(String value) {
    UserEntity user = new UserEntity();
    String name = "list-filter-" + UUID.randomUUID().toString().substring(0, 8);
    user.setName(name);
    user.setEmail(name + "@example.com");
    user.setLabels(labelled(value));
    return mongoTemplate.save(user);
  }

  private WorkspaceEntity workspace(String value) {
    WorkspaceEntity workspace = new WorkspaceEntity();
    workspace.setName("list-filter-ws-" + UUID.randomUUID().toString().substring(0, 8));
    workspace.setLabels(labelled(value));
    workspace = mongoTemplate.save(workspace);
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        workspace.getId(),
        workspace.getName(),
        Optional.empty(),
        Optional.empty());
    return workspace;
  }

  private WorkflowEntity workflow(String value) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName("list-filter-wf-" + UUID.randomUUID().toString().substring(0, 8));
    workflow.setLabels(labelled(value));
    workflow = mongoTemplate.save(workflow);
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setWorkflowRef(workflow.getId());
    revision.setVersion(1);
    mongoTemplate.save(revision);
    return workflow;
  }

  private TaskEntity task(String value) {
    TaskEntity task = new TaskEntity();
    task.setName("list-filter-task-" + UUID.randomUUID().toString().substring(0, 8));
    task.setType(TaskType.template);
    task.setLabels(labelled(value));
    task = mongoTemplate.save(task);
    TaskRevisionEntity revision = new TaskRevisionEntity();
    revision.setParentRef(task.getId());
    revision.setVersion(1);
    mongoTemplate.save(revision);
    return task;
  }

  // A starter is a system-workspace workflow of that type; the shared query reads it by type.
  private WorkflowEntity starter(String value) {
    WorkflowEntity starter = workflow(value);
    starter.setName("list-filter-tpl-" + UUID.randomUUID().toString().substring(0, 8));
    starter.setType(WorkflowType.starter);
    return mongoTemplate.save(starter);
  }

  private WorkflowRunEntity run(String workflowRef, String value) {
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, RunStatus.running, RunPhase.running);
    run.setLabels(new java.util.HashMap<>(labelled(value)));
    return workflowRunRepository.save(run);
  }

  private void approval(String workflowRef, Date created) {
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflowRef);
    action.setType(ActionType.approval);
    action.setStatus(ActionStatus.submitted);
    action.setCreationDate(created);
    mongoTemplate.save(action);
  }
}
