package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.api.WorkspaceTaskService;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a workspace must remove that workspace's own tasks and no others. The cascade resolves
 * a workspace's tasks through the TEAMTASK relationship (the type that actually anchors a task
 * under a workspace - TASK anchors the global catalogue under root instead) and deletes each by
 * workspace + name, the shape {@link WorkspaceTaskService#delete(String, String)} expects.
 *
 * <p>Fixtures wire the TEAMTASK/TASK relationship edges directly (mirroring what {@link
 * WorkspaceTaskService#create} does) rather than going through that method itself: its duplicate
 * check calls {@code RelationshipService.check()}, which - unrelated to anything under test here
 * - always answers "exists" when there is no principal on the SecurityContext, as in this test.
 */
class WorkspaceDeleteTaskCascadeTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private TaskService taskService;
  @Autowired private TaskRepository taskRepository;
  @Autowired private RelationshipService relationshipService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
  }

  @Test
  void deletingAWorkspaceRemovesOnlyItsOwnTasksNotOtherWorkspacesOrGlobalTasks() {
    String workspaceA = createWorkspace("cascade-workspace-a");
    String workspaceB = createWorkspace("cascade-workspace-b");

    createWorkspaceTask(workspaceA, "cascade-task-in-a");
    createWorkspaceTask(workspaceB, "cascade-task-in-b");
    createGlobalTask("cascade-task-global");

    workspaceService.delete(workspaceA);

    assertFalse(
        taskRepository.findByName("cascade-task-in-a").isPresent(),
        "the deleted workspace's own task must be removed");
    assertTrue(
        taskRepository.findByName("cascade-task-in-b").isPresent(),
        "a different workspace's task must survive the delete");
    assertTrue(
        taskRepository.findByName("cascade-task-global").isPresent(),
        "the global task catalogue must be untouched");
  }

  private String createWorkspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private void createWorkspaceTask(String team, String name) {
    Task task = taskService.create(newTask(name));
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        team,
        RelationshipLabel.HAS_TASK,
        RelationshipType.TEAMTASK,
        task.getId(),
        task.getName(),
        Optional.empty(),
        Optional.empty());
  }

  private void createGlobalTask(String name) {
    Task task = taskService.create(newTask(name));
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.HAS_TASK,
        RelationshipType.TASK,
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
