package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ExecutionCondition;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.workflow.TaskService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A run-workflow task as a for-each task: one child run per item, each carrying its item, and the
 * children's results collected into arrays on the parent; and a waited run-workflow task carrying
 * its child's results as its own, so a downstream task can read them.
 */
class ForeachChildRunTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "foreach-child-workspace";
  private static final List<Object> ITEMS = List.of("alpha", "beta", "gamma");

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;

  private String runWorkflowTaskRef;
  private String echoTaskRef;
  private String setPropertyTaskRef;

  @BeforeEach
  void seedCatalogueAndWorkspace() {
    if (relationshipService
        .filter(RelationshipType.WORKSPACE, Optional.of(List.of(WORKSPACE)))
        .isEmpty()) {
      relationshipService.createNode(
          RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, Optional.empty());
    }
    Task runWorkflow = new Task();
    runWorkflow.setName("foreach-run-workflow-" + System.nanoTime());
    runWorkflow.setType(TaskType.runworkflow);
    runWorkflow
        .getSpec()
        .setParams(List.of(param("workflowRef", "text"), param("wait", "boolean")));
    runWorkflowTaskRef = taskService.create(runWorkflow).getId();

    Task echo = new Task();
    echo.setName("foreach-echo-" + System.nanoTime());
    echo.setType(TaskType.template);
    echo.getSpec().setImage("busybox:latest");
    echo.getSpec().setCommand(List.of("echo"));
    echo.getSpec().setParams(List.of(param("msg", "text")));
    echoTaskRef = taskService.create(echo).getId();

    Task setProperty = new Task();
    setProperty.setName("foreach-set-property-" + System.nanoTime());
    setProperty.setType(TaskType.setwfproperty);
    setProperty.getSpec().setParams(List.of(param("output", "text"), param("value", "text")));
    setPropertyTaskRef = taskService.create(setProperty).getId();
  }

  @Test
  void eachItemSubmitsAChildRunCarryingItsItem() {
    String childWorkflowId = createLeafWorkflow("child-per-item");
    String parentRunId = submit(createForeachParent("parent-per-item", childWorkflowId, true));

    List<TaskRunEntity> items = awaitItemsParked(parentRunId);

    assertEquals(ITEMS.size(), items.size());
    for (int index = 0; index < ITEMS.size(); index++) {
      WorkflowRunEntity child = childRunOf(items.get(index));
      assertEquals("task", child.getTrigger());
      assertEquals(items.get(index).getId(), child.getInitiatedByRef());
      assertEquals(ITEMS.get(index), paramValue(child, "item"));
      assertEquals(String.valueOf(index), paramValue(child, "index"));
      assertNull(paramValue(child, "workflowRef"));
      assertNull(paramValue(child, "wait"));
    }
  }

  @Test
  void aWaitedForeachCollectsTheChildrensResultsInItemOrder() {
    String childWorkflowId = createLeafWorkflow("child-collect");
    String parentRunId = submit(createForeachParent("parent-collect", childWorkflowId, true));
    List<TaskRunEntity> items = awaitItemsParked(parentRunId);

    items.forEach(item -> endLeafRun(childRunOf(item).getId(), RunStatus.succeeded));

    awaitEngine("for-each parent succeeded with its children's results")
        .untilAsserted(
            () -> {
              TaskRunEntity parent = parentTask(parentRunId);
              assertEquals(RunStatus.succeeded, parent.getStatus());
              assertEquals(RunPhase.completed, parent.getPhase());
            });
    TaskRunEntity parent = parentTask(parentRunId);
    assertEquals(
        List.of("done", "done", "done"), resultValue(parent, "greeting"));
    assertEquals(
        items.stream().map(item -> childRunOf(item).getId()).toList(),
        resultValue(parent, "workflowRunRef"));
    awaitEngine("parent run completed")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.succeeded,
                    workflowRunRepository.findById(parentRunId).orElseThrow().getStatus()));
  }

  @Test
  void aFailedChildFailsItsItemAndTheParentEndsItemFailedWithANullSlot() {
    String childWorkflowId = createLeafWorkflow("child-one-fails");
    String parentRunId = submit(createForeachParent("parent-one-fails", childWorkflowId, true));
    List<TaskRunEntity> items = awaitItemsParked(parentRunId);

    endLeafRun(childRunOf(items.get(0)).getId(), RunStatus.succeeded);
    endLeafRun(childRunOf(items.get(1)).getId(), RunStatus.failed);
    endLeafRun(childRunOf(items.get(2)).getId(), RunStatus.succeeded);

    awaitEngine("for-each parent failed on the failed item")
        .untilAsserted(
            () -> {
              TaskRunEntity parent = parentTask(parentRunId);
              assertEquals(RunStatus.failed, parent.getStatus());
              assertEquals("ItemFailed", parent.getStatusReason());
            });
    TaskRunEntity failedItem =
        taskRunRepository.findById(items.get(1).getId()).orElseThrow();
    assertEquals(RunStatus.failed, failedItem.getStatus());
    assertEquals("ChildRunFailed", failedItem.getStatusReason());
    List<Object> greetings = new LinkedList<>();
    greetings.add("done");
    greetings.add(null);
    greetings.add("done");
    assertEquals(greetings, resultValue(parentTask(parentRunId), "greeting"));
  }

  @Test
  void cancellingTheParentRunCancelsEveryItemsChild() {
    String childWorkflowId = createLeafWorkflow("child-cascade");
    String parentRunId = submit(createForeachParent("parent-cascade", childWorkflowId, true));
    List<TaskRunEntity> items = awaitItemsParked(parentRunId);
    List<String> childRunIds = items.stream().map(item -> childRunOf(item).getId()).toList();

    workflowRunService.cancel(parentRunId);

    awaitEngine("every item's child cancelled with the parent")
        .untilAsserted(
            () ->
                childRunIds.forEach(
                    id ->
                        assertEquals(
                            RunStatus.cancelled,
                            workflowRunRepository.findById(id).orElseThrow().getStatus())));
  }

  @Test
  void aPlainWaitedRunWorkflowExposesItsChildsResultDownstream() {
    String childWorkflowId = createLeafWorkflow("child-downstream");
    String parentRunId = submit(createParentWithDownstream("parent-downstream", childWorkflowId));

    awaitEngine("run-workflow task parked")
        .untilAsserted(
            () -> {
              TaskRunEntity task = parentTask(parentRunId);
              assertNotNull(task);
              assertEquals(RunStatus.waiting, task.getStatus());
              assertNotNull(resultValue(task, "workflowRunRef"));
            });
    endLeafRun(childRunOf(parentTask(parentRunId)).getId(), RunStatus.succeeded);

    awaitEngine("downstream task admitted with the child's result")
        .untilAsserted(
            () -> {
              TaskRunEntity after =
                  taskRunRepository
                      .findFirstByNameAndWorkflowRunRef("after", parentRunId)
                      .orElseThrow();
              assertEquals(RunStatus.ready, after.getStatus());
              assertEquals("done", paramValue(after, "msg"));
            });
    assertEquals("done", resultValue(parentTask(parentRunId), "greeting"));
  }

  @Test
  void saveStillRefusesForeachOnStartAndEnd() {
    WorkflowTask start = node("start", TaskType.start, null, null, null);
    WorkflowTask end = node("end", TaskType.end, null, "start", null);
    WorkflowTaskForeach foreach = new WorkflowTaskForeach();
    foreach.setItems(ITEMS);
    end.setForeach(foreach);
    Workflow workflow = new Workflow();
    workflow.setName("foreach-on-end-" + System.nanoTime());
    workflow.setTasks(new LinkedList<>(List.of(start, end)));

    assertThrows(BoomerangException.class, () -> workflowService.create(workflow, false));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String submit(String workflowId) {
    return workflowService.submit(workflowId, new WorkflowSubmitRequest(), true).getId();
  }

  /** The for-each parent, or the plain run-workflow task, of a run: the one with no parentRef. */
  private TaskRunEntity parentTask(String workflowRunId) {
    return taskRunRepository.findByWorkflowRunRef(workflowRunId).stream()
        .filter(t -> TaskType.runworkflow.equals(t.getType()) && t.getParentRef() == null)
        .findFirst()
        .orElse(null);
  }

  /** The items of the run's for-each task, in index order. */
  private List<TaskRunEntity> items(String workflowRunId) {
    return taskRunRepository.findByWorkflowRunRef(workflowRunId).stream()
        .filter(t -> t.getParentRef() != null)
        .sorted(Comparator.comparing(TaskRunEntity::getIndex))
        .toList();
  }

  private List<TaskRunEntity> awaitItemsParked(String parentRunId) {
    awaitEngine("every item parked on its child")
        .untilAsserted(
            () -> {
              List<TaskRunEntity> items = items(parentRunId);
              assertEquals(ITEMS.size(), items.size());
              items.forEach(
                  item -> {
                    assertEquals(RunStatus.waiting, item.getStatus());
                    assertNotNull(resultValue(item, "workflowRunRef"));
                  });
            });
    return items(parentRunId);
  }

  private WorkflowRunEntity childRunOf(TaskRunEntity task) {
    return workflowRunRepository
        .findById(String.valueOf(resultValue(task, "workflowRunRef")))
        .orElseThrow();
  }

  /** Drive a leaf run's template task to the given terminal status, as a dispatcher would. */
  private void endLeafRun(String leafRunId, RunStatus status) {
    awaitEngine("leaf template TaskRun ready")
        .untilAsserted(
            () ->
                assertTrue(
                    taskRunRepository
                        .findFirstByNameAndWorkflowRunRef("work", leafRunId)
                        .isPresent()));
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", leafRunId).orElseThrow().getId();
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(status);
    taskRunService.end(taskRunId, Optional.of(endRequest));
  }

  private static Object resultValue(TaskRunEntity taskRun, String name) {
    if (taskRun == null || taskRun.getResults() == null) {
      return null;
    }
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static Object paramValue(WorkflowRunEntity run, String name) {
    return run.getParams().stream()
        .filter(p -> name.equalsIgnoreCase(p.getName()))
        .map(RunParam::getValue)
        .findFirst()
        .orElse(null);
  }

  private static Object paramValue(TaskRunEntity taskRun, String name) {
    return taskRun.getParams().stream()
        .filter(p -> name.equalsIgnoreCase(p.getName()))
        .map(RunParam::getValue)
        .findFirst()
        .orElse(null);
  }

  /**
   * start -> work (template, parks awaiting a dispatcher) -> set (writes the run result greeting =
   * done) -> end, each edge requiring success, so a failed work fails the run and writes nothing.
   */
  private String createLeafWorkflow(String name) {
    WorkflowTask work = node("work", TaskType.template, echoTaskRef, "start", null);
    WorkflowTask set =
        node(
            "set",
            TaskType.setwfproperty,
            setPropertyTaskRef,
            "work",
            new LinkedList<>(
                List.of(new RunParam("output", "greeting"), new RunParam("value", "done"))));
    set.getDependencies().get(0).setExecutionCondition(ExecutionCondition.success);
    return createWorkflow(name, List.of(work, set), "set", ExecutionCondition.success);
  }

  /** start -> child (run-workflow for each item, waiting) -> end. */
  private String createForeachParent(String name, String childWorkflowId, boolean wait) {
    WorkflowTask child = runWorkflowNode(childWorkflowId, wait);
    WorkflowTaskForeach foreach = new WorkflowTaskForeach();
    foreach.setItems(ITEMS);
    child.setForeach(foreach);
    return createWorkflow(name, List.of(child), "child", ExecutionCondition.always);
  }

  /** start -> child (run-workflow, waiting) -> after (reads the child's greeting) -> end. */
  private String createParentWithDownstream(String name, String childWorkflowId) {
    WorkflowTask child = runWorkflowNode(childWorkflowId, true);
    WorkflowTask after =
        node(
            "after",
            TaskType.template,
            echoTaskRef,
            "child",
            new LinkedList<>(List.of(new RunParam("msg", "$(tasks.child.results.greeting)"))));
    return createWorkflow(name, List.of(child, after), "after", ExecutionCondition.always);
  }

  private WorkflowTask runWorkflowNode(String childWorkflowId, boolean wait) {
    return node(
        "child",
        TaskType.runworkflow,
        runWorkflowTaskRef,
        "start",
        new LinkedList<>(
            List.of(
                new RunParam("workflowRef", childWorkflowId),
                new RunParam("wait", Boolean.toString(wait)))));
  }

  /*
   * Created unscoped then anchored under a workspace node, so the engine can resolve a child run's
   * owning workspace through the workflow's edge.
   */
  private String createWorkflow(
      String name, List<WorkflowTask> middle, String last, ExecutionCondition endCondition) {
    WorkflowTask end = node("end", TaskType.end, null, last, null);
    end.getDependencies().get(0).setExecutionCondition(endCondition);
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + System.nanoTime());
    List<WorkflowTask> tasks = new LinkedList<>();
    tasks.add(node("start", TaskType.start, null, null, null));
    tasks.addAll(middle);
    tasks.add(end);
    workflow.setTasks(tasks);
    Workflow created = workflowService.create(workflow, false).getBody();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        WORKSPACE,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        created.getId(),
        created.getName(),
        Optional.empty(),
        Optional.empty());
    return created.getId();
  }

  private static WorkflowTask node(
      String name, TaskType type, String taskRef, String dependsOn, List<RunParam> params) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (params != null) {
      task.setParams(params);
    }
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }

  private static AbstractParam param(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }
}
