package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * A path that names a Task nobody has answers 404; a blank or malformed name in the request stays
 * 400. Both conditions used to share {@code TASK_INVALID_NAME}, so a typo in a URL was
 * indistinguishable from a bad name - {@code get} already answered 404 while {@code changelog} and
 * {@code delete} answered 400 for the very same "no such Task".
 */
class TaskNotFoundStatusTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-status-ws";
  private static final String UNKNOWN = "task-status-unknown";

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
  void everyLookupOfATaskNobodyHasAnswersNotFound() {
    assertNotFound(() -> taskService.get(WORKSPACE, UNKNOWN, Optional.empty()), "scoped get");
    assertNotFound(() -> taskService.changelog(WORKSPACE, UNKNOWN), "scoped changelog");
    assertNotFound(() -> taskService.delete(WORKSPACE, UNKNOWN), "scoped delete");
    assertNotFound(() -> taskService.getGlobal(UNKNOWN, Optional.empty()), "global get");
    assertNotFound(() -> taskService.changelogGlobal(UNKNOWN), "global changelog");
    assertNotFound(() -> taskService.deleteGlobal(UNKNOWN), "global delete");
  }

  @Test
  void aMalformedNameStaysABadRequest() {
    assertBadName(() -> taskService.changelog(WORKSPACE, "bad name!"), "scoped changelog");
    assertBadName(
        () -> taskService.apply("bad name!", WORKSPACE, malformed(), false), "scoped apply");
    assertBadName(() -> taskService.applyGlobal("bad name!", malformed(), false), "global apply");
    assertBadName(() -> taskService.createGlobal(malformed()), "global create");
  }

  /** A blank name is a request the caller can fix, not a missing resource. */
  @Test
  void aBlankNameOnDeleteStaysABadRequest() {
    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> taskService.delete(WORKSPACE, "  "));
    assertEquals("TASK_INVALID_REQ", ex.getReason());
    assertEquals(400, ex.getStatus().value());
  }

  private void assertNotFound(Executable operation, String label) {
    BoomerangException ex = assertThrows(BoomerangException.class, operation, label);
    assertEquals("TASK_INVALID_REFERENCE", ex.getReason(), label + " must report a missing Task");
    assertEquals(404, ex.getStatus().value(), label + " must answer 404");
  }

  private void assertBadName(Executable operation, String label) {
    BoomerangException ex = assertThrows(BoomerangException.class, operation, label);
    assertEquals("TASK_INVALID_NAME", ex.getReason(), label + " must report a malformed name");
    assertEquals(400, ex.getStatus().value(), label + " must answer 400");
  }

  private static Task malformed() {
    Task task = new Task();
    task.setName("bad name!");
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    return task;
  }
}
