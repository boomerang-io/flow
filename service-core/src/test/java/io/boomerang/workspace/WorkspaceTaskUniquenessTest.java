package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.api.WorkspaceTaskService;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Task-slug uniqueness used to be checked with {@code RelationshipService.check()}, which answers
 * "may this caller act on this?", not "does this already exist?" - with no principal on the
 * SecurityContext (as here) it always answers true, so creation failed with TASK_ALREADY_EXISTS
 * on every single call, whether or not a name actually collided. The fix is a pure existence
 * lookup: creation must succeed with no collision and still reject a real duplicate, both within
 * a workspace (TEAMTASK) and in the global catalogue (TASK).
 */
class WorkspaceTaskUniquenessTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceTaskService workspaceTaskService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    // WorkspaceTaskService.internalCreate touches the changelog author off the current identity -
    // a global principal keeps that path populated without needing a workspace membership edge.
    seedGlobalIdentity();
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void creatingATeamTaskWithAUniqueNameSucceeds() {
    String workspace = createWorkspace("task-uniqueness-a");

    Task task = workspaceTaskService.create(workspace, newTask("unique-team-task"));

    assertNotNull(task);
    assertEquals("unique-team-task", task.getName());
  }

  @Test
  void creatingATeamTaskWithADuplicateNameInTheSameWorkspaceIsRejected() {
    String workspace = createWorkspace("task-uniqueness-b");
    workspaceTaskService.create(workspace, newTask("duplicate-team-task"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workspaceTaskService.create(workspace, newTask("duplicate-team-task")));
    assertEquals("TASK_ALREADY_EXISTS", ex.getReason());
  }

  @Test
  void creatingAGlobalTaskWithAUniqueNameSucceeds() {
    Task task = workspaceTaskService.create(newTask("unique-global-task"));

    assertNotNull(task);
    assertEquals("unique-global-task", task.getName());
  }

  @Test
  void creatingAGlobalTaskWithADuplicateNameIsRejected() {
    workspaceTaskService.create(newTask("duplicate-global-task"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workspaceTaskService.create(newTask("duplicate-global-task")));
    assertEquals("TASK_ALREADY_EXISTS", ex.getReason());
  }

  private void seedGlobalIdentity() {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("task-uniqueness-test-principal");
    Authentication authentication = new UsernamePasswordAuthenticationToken(admin.getPrincipal(), null);
    ((UsernamePasswordAuthenticationToken) authentication).setDetails(admin);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
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
