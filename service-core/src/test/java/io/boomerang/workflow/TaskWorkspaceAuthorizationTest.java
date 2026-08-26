package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The workspace guard on the {@code /api/v2/workspace/&#123;workspace&#125;/task} operations.
 *
 * <p>F3 moved this guard from the deleted {@code api.WorkspaceTaskService} pass-through into {@link
 * TaskService} itself. The check was untested at the pass-through, so its call path changed shape
 * with nothing pinning it: this test pins that every workspace-scoped operation still refuses a
 * Task the caller cannot reach through the named workspace, that creating into an unreachable
 * workspace is refused outright, and that a caller who can reach a Task is unaffected.
 *
 * <p>The identity is deliberately {@code session}-scope with a real {@code MEMBER_OF} edge: {@code
 * RelationshipService.check} returns {@code true} unconditionally for a global-scope token
 * (RelationshipService:417-419), so the base class's global identity cannot exercise a guard at
 * all.
 */
class TaskWorkspaceAuthorizationTest extends AbstractEngineIntegrationTest {

  private static final String MEMBER = "task-authz-member";
  private static final String MY_WORKSPACE = "task-authz-my-ws";
  private static final String FOREIGN_WORKSPACE = "task-authz-foreign-ws";
  private static final String MY_TASK = "task-authz-mine";
  private static final String FOREIGN_TASK = "task-authz-theirs";
  private static final String GLOBAL_TASK = "task-authz-global";

  @Autowired private TaskService taskService;
  @Autowired private TaskRepository taskRepository;

  /**
   * Replaces the base class's global identity - runs after {@code establishTestIdentity}. The
   * fixtures are seeded first, while that global identity is still in place, because seeding writes
   * relationship edges the member is not entitled to write.
   */
  @BeforeEach
  void establishMemberIdentity() {
    seedRelationshipRoot();
    relationshipService.createNode(RelationshipType.USER, MEMBER, MEMBER, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, MY_WORKSPACE, MY_WORKSPACE, Optional.empty());
    relationshipService.createNode(
        RelationshipType.WORKSPACE, FOREIGN_WORKSPACE, FOREIGN_WORKSPACE, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        MEMBER,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        MY_WORKSPACE,
        Optional.empty());

    workspaceTask(MY_WORKSPACE, MY_TASK);
    workspaceTask(FOREIGN_WORKSPACE, FOREIGN_TASK);
    seedGlobalTask(GLOBAL_TASK);

    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(MEMBER);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(MEMBER, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  void everyScopedOperationRefusesATaskTheCallerCannotReachThroughThatWorkspace() {
    assertRefused(
        () -> taskService.get(MY_WORKSPACE, FOREIGN_TASK, Optional.empty()),
        "TASK_INVALID_REFERENCE",
        "get");
    assertRefused(
        () -> taskService.changelog(MY_WORKSPACE, FOREIGN_TASK), "TASK_INVALID_NAME", "changelog");
    assertRefused(
        () -> taskService.getAsTekton(MY_WORKSPACE, FOREIGN_TASK, Optional.empty()),
        "TASK_INVALID_REFERENCE",
        "getAsTekton");
    assertRefused(
        () -> taskService.delete(MY_WORKSPACE, FOREIGN_TASK), "TASK_INVALID_NAME", "delete");

    // Refused before any work: the foreign Task is untouched.
    assertTrue(
        taskRepository.findByName(FOREIGN_TASK).isPresent(),
        "a refused delete must not remove the Task");
  }

  @Test
  void aTaskIsAlsoRefusedThroughAWorkspaceTheCallerIsNotAMemberOf() {
    // The Task IS in FOREIGN_WORKSPACE, but the caller has no path to that workspace at all.
    assertRefused(
        () -> taskService.get(FOREIGN_WORKSPACE, FOREIGN_TASK, Optional.empty()),
        "TASK_INVALID_REFERENCE",
        "get");
  }

  @Test
  void creatingIntoAWorkspaceTheCallerCannotReachIsRefused() {
    assertRefused(
        () -> taskService.create(FOREIGN_WORKSPACE, newTask("task-authz-intruder")),
        "PERMISSION_DENIED",
        "create");
    assertTrue(
        taskRepository.findByName("task-authz-intruder").isEmpty(),
        "a refused create must not persist the Task");
  }

  @Test
  void aTaskTheCallerCanReachThroughThatWorkspaceIsServed() {
    assertEquals(
        MY_TASK,
        taskService.get(MY_WORKSPACE, MY_TASK, Optional.empty()).getName(),
        "the guard must not reject a Task reachable through the named workspace");
  }

  @Test
  void theScopedQueryReturnsOnlyTheNamedWorkspacesTasks() {
    List<String> names =
        taskService
            .query(
                MY_WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getContent()
            .stream()
            .map(Task::getName)
            .toList();
    assertTrue(names.contains(MY_TASK), "the caller's own workspace task must be returned");
    assertTrue(
        !names.contains(FOREIGN_TASK), "another workspace's task must never appear in the page");
  }

  /**
   * The global catalogue is deliberately NOT workspace-narrowed: {@code
   * RelationshipService.filter} anchors {@code TASK} at the root node for every principal ("Tasks
   * are a global catalogue", RelationshipService:476-480). Pinned so that the asymmetry between the
   * two scoped shapes is a recorded decision rather than an accident of the collapse.
   */
  @Test
  void theGlobalCatalogueIsVisibleToAWorkspaceMember() {
    assertEquals(
        GLOBAL_TASK, taskService.getGlobal(GLOBAL_TASK, Optional.empty()).getName());
  }

  private void assertRefused(Executable operation, String reason, String label) {
    BoomerangException ex =
        assertThrows(BoomerangException.class, operation, label + " must be refused");
    assertEquals(reason, ex.getReason(), label + " must fail with the expected error");
  }

  // Idempotent, like seedGlobalTask: @BeforeEach runs per test method against one shared
  // Testcontainers database, and a second TaskEntity of the same name breaks findByName.
  private void workspaceTask(String workspace, String name) {
    if (taskRepository.existsByName(name)) {
      return;
    }
    Task task = taskService.create(newTask(name));
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_TASK,
        RelationshipType.TEAMTASK,
        task.getId(),
        task.getName(),
        Optional.empty(),
        Optional.empty());
  }

  private static Task newTask(String name) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    return task;
  }
}
