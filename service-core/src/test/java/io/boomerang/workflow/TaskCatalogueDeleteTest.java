package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Task;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The global catalogue can be pruned, not just made inactive: {@code DELETE /api/v2/task/{name}}
 * answers through the same delete the workspace-scoped route uses, which refuses while a run in
 * flight still references the Task - an unpinned workflow node resolves "latest" at run time and
 * would lose its definition.
 */
class TaskCatalogueDeleteTest extends AbstractEngineIntegrationTest {

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
  }

  @Test
  void deletingACatalogueTaskRemovesIt() {
    String name = "catalogue-delete-removed";
    createTask(name);

    taskService.deleteGlobal(name);

    assertThrows(BoomerangException.class, () -> taskService.getGlobal(name, Optional.empty()));
  }

  @Test
  void deletingACatalogueTaskAnInFlightRunReferencesIsRefused() {
    String name = "catalogue-delete-in-use";
    String taskRef = createTask(name);
    TaskRunEntity run = new TaskRunEntity();
    run.setName("in-flight");
    run.setType(TaskType.template);
    run.setStatus(RunStatus.running);
    run.setPhase(RunPhase.running);
    run.setCreationDate(new Date());
    run.setTaskRef(taskRef);
    taskRunRepository.save(run);

    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> taskService.deleteGlobal(name));

    assertEquals("TASK_DELETE_IN_USE", ex.getReason());
    assertEquals(409, ex.getStatus().value());
  }

  @Test
  void deletingAnUnknownCatalogueTaskIsRejected() {
    assertThrows(
        BoomerangException.class, () -> taskService.deleteGlobal("catalogue-delete-unknown"));
  }

  private String createTask(String name) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    taskService.createGlobal(task);
    return relationshipService
        .filter(RelationshipType.TASK, Optional.of(List.of(name)))
        .get(0);
  }
}
