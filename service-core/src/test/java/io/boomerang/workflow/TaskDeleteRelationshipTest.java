package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a Task takes its relationship node and edges with it. The Task and its revisions were
 * always removed, but the node the create wrote stayed behind: the name then stayed taken as far
 * as the graph was concerned, and the workspace kept an edge to a node whose Task no longer
 * exists.
 */
class TaskDeleteRelationshipTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-delete-rel-ws";

  @Autowired private RelationshipNodeRepository nodeRepository;
  @Autowired private RelationshipEdgeRepository edgeRepository;
  @Autowired private TaskRepository taskRepository;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    // Under the root, as WorkspaceService.create writes it - the scoped filter walks down from
    // root for a global identity, so a bare node is unreachable.
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        WORKSPACE,
        WORKSPACE,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void deletingAWorkspaceTaskRemovesItsNodeAndTheWorkspaceEdge() {
    String name = "task-delete-rel-scoped";
    taskService.create(WORKSPACE, newTask(name));
    String nodeId = nodeId(RelationshipType.TEAMTASK, name);
    assertTrue(
        edgesTouching(nodeId).size() == 1, "the create must have written the workspace's edge");

    taskService.delete(WORKSPACE, name);

    assertFalse(
        relationshipService.doesSlugOrRefExistForType(RelationshipType.TEAMTASK, name),
        "no rel_nodes row may survive the delete");
    assertTrue(edgesTouching(nodeId).isEmpty(), "no rel_edges row may point at the deleted node");
  }

  @Test
  void deletingACatalogueTaskRemovesItsNodeAndTheRootEdge() {
    String name = "task-delete-rel-global";
    taskService.createGlobal(newTask(name));
    String nodeId = nodeId(RelationshipType.TASK, name);
    assertTrue(edgesTouching(nodeId).size() == 1, "the create must have written the root's edge");

    taskService.deleteGlobal(name);

    assertFalse(
        relationshipService.doesSlugOrRefExistForType(RelationshipType.TASK, name),
        "no rel_nodes row may survive the delete");
    assertTrue(edgesTouching(nodeId).isEmpty(), "no rel_edges row may point at the deleted node");
  }

  /**
   * The unscoped delete serves callers that never wrote a relationship node - the engine and the
   * workflow-definition path create Tasks through {@code create(Task)}, which writes no node. It
   * must stay graph-free rather than resolving one that is not there.
   */
  @Test
  void deletingATaskThatWasNeverInTheGraphDoesNotThrow() {
    String name = "task-delete-rel-ungraphed";
    Task created = taskService.create(newTask(name));
    assertFalse(
        relationshipService.doesSlugOrRefExistForType(RelationshipType.TEAMTASK, name),
        "the unscoped create writes no relationship node");

    assertDoesNotThrow(() -> taskService.delete(created.getId()));
    assertTrue(taskRepository.findByName(name).isEmpty(), "the Task itself is still removed");
  }

  private String nodeId(RelationshipType type, String slug) {
    RelationshipNodeEntity node =
        nodeRepository.findOneByTypeAndRefOrSlug(type.getLabel(), slug).orElseThrow();
    return node.getId();
  }

  private List<RelationshipEdgeEntity> edgesTouching(String nodeId) {
    return edgeRepository.findAll().stream()
        .filter(e -> nodeId.equals(e.getFrom()) || nodeId.equals(e.getTo()))
        .toList();
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
