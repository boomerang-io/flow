package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.TaskExecutionService;
import io.boomerang.workflow.model.WorkflowCanvas;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The definition-side guard on a task's foreach setting, and the reservation of "[" and "]" in
 * task names that keeps an item's name, {@code <name>[<index>]}, from colliding with another task.
 */
class WorkflowTaskForeachValidationTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-foreach-validation-ws";

  @Autowired private WorkflowService workflowService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    relationshipService.createNode(
        RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, java.util.Optional.empty());
    setFeatureSetting("workspaceQuotas", false);
  }

  @Test
  void aSingleReferenceSavesAndRoundTrips() {
    String taskSlug = template("foreach-validation-reference", null);
    Workflow saved =
        workflowService.create(
            WORKSPACE, workflowWith("foreach-validation-reference-wf", "work", taskSlug, "$(params.repos)"));

    assertEquals("$(params.repos)", workTask(saved, "work").getForeach().getItems());
  }

  @Test
  void aLiteralArraySavesAndRoundTrips() {
    String taskSlug = template("foreach-validation-literal", null);
    Workflow saved =
        workflowService.create(
            WORKSPACE, workflowWith("foreach-validation-literal-wf", "work", taskSlug, List.of("a", "b")));

    assertEquals(List.of("a", "b"), workTask(saved, "work").getForeach().getItems());
  }

  @Test
  void aLiteralArrayOverTheCapIsRejected() {
    String taskSlug = template("foreach-validation-over-cap", null);
    List<Integer> tooMany =
        IntStream.rangeClosed(0, TaskExecutionService.DEFAULT_MAX_FOREACH_ITEMS).boxed().toList();

    assertRejected(workflowWith("foreach-validation-over-cap-wf", "work", taskSlug, tooMany));
  }

  @Test
  void itemsThatAreNeitherAnArrayNorASingleReferenceAreRejected() {
    String taskSlug = template("foreach-validation-shape", null);

    assertRejected(workflowWith("foreach-validation-text-wf", "work", taskSlug, "a,b,c"));
    assertRejected(
        workflowWith("foreach-validation-two-refs-wf", "work", taskSlug, "$(params.a)$(params.b)"));
    assertRejected(
        workflowWith("foreach-validation-object-wf", "work", taskSlug, Map.of("a", "b")));
  }

  @Test
  void aTemplateDeclaringItemOrIndexIsRejected() {
    assertRejected(
        workflowWith(
            "foreach-validation-item-wf", "work", template("foreach-validation-item", "item"), List.of("a")));
    assertRejected(
        workflowWith(
            "foreach-validation-index-wf", "work", template("foreach-validation-index", "Index"), List.of("a")));
  }

  @Test
  void aTypeADispatcherDoesNotRunIsRejected() {
    String taskSlug = "foreach-validation-approval-" + System.nanoTime();
    Task approval = new Task();
    approval.setName(taskSlug);
    approval.setType(TaskType.approval);
    taskService.createGlobal(approval);
    Workflow workflow = workflowWith("foreach-validation-approval-wf", "work", taskSlug, List.of("a"));
    workTask(workflow, "work").setType(TaskType.approval);

    assertRejected(workflow);
  }

  @Test
  void aTaskNameWithABracketIsRejected() {
    String taskSlug = template("foreach-validation-name", null);
    Workflow workflow = workflowWith("foreach-validation-name-wf", "locate[0]", taskSlug, null);

    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));

    assertEquals("WORKFLOW_INVALID_TASK_NAME", ex.getReason());
  }

  @Test
  void theCanvasCarriesForeachBothWays() {
    Workflow workflow = workflowWith("foreach-canvas-wf", "work", "some-task", "$(params.repos)");
    workflow.getTasks().forEach(t -> t.getAnnotations().put("boomerang.io/position", Map.of("x", 0, "y", 0)));

    WorkflowCanvas canvas = workflowService.convertWorkflowToCanvas(workflow);
    Workflow back = workflowService.convertCanvasToWorkflow(canvas);

    assertEquals("$(params.repos)", workTask(back, "work").getForeach().getItems());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void assertRejected(Workflow workflow) {
    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));
    assertEquals("WORKFLOW_INVALID_TASK_FOREACH", ex.getReason());
  }

  private static WorkflowTask workTask(Workflow workflow, String name) {
    return workflow.getTasks().stream().filter(t -> name.equals(t.getName())).findFirst().orElseThrow();
  }

  /** start -> {@code name} (foreach over items when given) -> end. */
  private static Workflow workflowWith(String workflowName, String name, String taskSlug, Object items) {
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);

    WorkflowTask work = new WorkflowTask();
    work.setName(name);
    work.setType(TaskType.template);
    work.setTaskRef(taskSlug);
    work.setDependencies(new LinkedList<>(List.of(dependencyOn("start"))));
    if (items != null) {
      WorkflowTaskForeach foreach = new WorkflowTaskForeach();
      foreach.setItems(items);
      work.setForeach(foreach);
    }

    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    end.setDependencies(new LinkedList<>(List.of(dependencyOn(name))));

    Workflow workflow = new Workflow();
    workflow.setName(workflowName);
    workflow.setTasks(new LinkedList<>(List.of(start, work, end)));
    return workflow;
  }

  private static WorkflowTaskDependency dependencyOn(String taskRef) {
    WorkflowTaskDependency dependency = new WorkflowTaskDependency();
    dependency.setTaskRef(taskRef);
    return dependency;
  }

  /** A global template Task (created once per name) declaring {@code paramName} when given. */
  private String template(String name, String paramName) {
    if (!relationshipService
        .filter(RelationshipType.TASK, java.util.Optional.of(List.of(name)))
        .isEmpty()) {
      return name;
    }
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    if (paramName != null) {
      AbstractParam param = new AbstractParam();
      param.setName(paramName);
      param.setType("text");
      task.getSpec().setParams(new LinkedList<>(List.of(param)));
    }
    taskService.createGlobal(task);
    return name;
  }
}
