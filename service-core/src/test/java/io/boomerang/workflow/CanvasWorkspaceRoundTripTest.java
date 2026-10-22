package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.model.WorkflowCanvas;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The editor reads and writes a workflow through its canvas shape. Everything the workspace
 * change put on the workflow must survive that round trip: the run workspace itself, a task's
 * opt-out (`workspace: false`, which only the API or YAML can author), and the read-only marker
 * the v3 upgrade sets, which the editor shows and a save clears.
 */
class CanvasWorkspaceRoundTripTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkflowService workflowService;

  @Test
  void theWorkspaceTheTaskOptOutAndTheLegacyMarkerSurviveTheCanvas() {
    Workflow workflow = new Workflow();
    workflow.setName("canvas-workspace-round-trip");
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setSize("2Gi");
    workspace.setMountPath("/workspace");
    workflow.setWorkspace(workspace);
    workflow.setLegacyWorkflowStorage(true);
    WorkflowTask optedOut = task("no-storage", TaskType.template, "start");
    optedOut.setWorkspace(false);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                task("start", TaskType.start, null),
                optedOut,
                task("with-storage", TaskType.template, "no-storage"),
                task("end", TaskType.end, "with-storage"))));

    WorkflowCanvas canvas = workflowService.convertWorkflowToCanvas(workflow);

    assertEquals("2Gi", canvas.getWorkspace().getSize());
    assertEquals(Boolean.TRUE, canvas.getLegacyWorkflowStorage(), "the editor can show the marker");
    assertEquals(
        Boolean.FALSE,
        node(canvas, "no-storage").getData().getWorkspace(),
        "an authored opt-out reaches the canvas");
    assertNull(node(canvas, "with-storage").getData().getWorkspace(), "absent means mount");

    Workflow back = workflowService.convertCanvasToWorkflow(canvas);

    assertEquals("2Gi", back.getWorkspace().getSize());
    assertEquals(
        Boolean.FALSE,
        taskNamed(back, "no-storage").getWorkspace(),
        "a save from the editor keeps the opt-out");
    assertNull(taskNamed(back, "with-storage").getWorkspace());
  }

  private static io.boomerang.workflow.model.CanvasNode node(WorkflowCanvas canvas, String name) {
    return canvas.getNodes().stream()
        .filter(n -> name.equals(n.getData().getName()))
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowTask taskNamed(Workflow workflow, String name) {
    return workflow.getTasks().stream()
        .filter(t -> name.equals(t.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowTask task(String name, TaskType type, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(name);
    // The editor always sends a position; the canvas conversion back relies on it.
    task.getAnnotations().put("boomerang.io/position", Map.of("x", 0, "y", 0));
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }
}
