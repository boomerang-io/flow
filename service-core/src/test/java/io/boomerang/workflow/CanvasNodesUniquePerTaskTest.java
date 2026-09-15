package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.model.WorkflowCanvas;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * boomerang-io/flow#239: the v4 Workflow Revision API duplicated every record in
 * .config.nodes[]. v5 has no stored config.nodes at all - the canvas nodes are derived
 * 1:1 from the revision's tasks by WorkflowService.convertWorkflowToCanvas. This pins
 * that a workflow with N tasks composes to exactly N canvas nodes with unique names.
 */
class CanvasNodesUniquePerTaskTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;

  @Test
  void composeProducesExactlyOneNodePerTask() {
    Workflow workflow = new Workflow();
    workflow.setName("canvas-unique-nodes");
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                task("start", TaskType.start, null),
                task("hello", TaskType.template, "start"),
                task("world", TaskType.template, "hello"),
                task("end", TaskType.end, "world"))));

    WorkflowCanvas canvas = workflowService.convertWorkflowToCanvas(workflow);

    assertEquals(4, canvas.getNodes().size(), "one canvas node per task - no duplicates");
    Set<String> names =
        canvas.getNodes().stream().map(n -> n.getData().getName()).collect(Collectors.toSet());
    assertEquals(
        Set.of("start", "hello", "world", "end"),
        names,
        "every task appears exactly once in .nodes[]");
    assertEquals(
        3, canvas.getEdges().size(), "one edge per declared dependency, none duplicated");
  }

  private static WorkflowTask task(String name, TaskType type, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(name);
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }
}
