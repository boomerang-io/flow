package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The definition-side guard on {@code WorkflowService.createWorkflowRevisionEntity}: a workflow
 * task node's params must be a subset of the params its referenced Task Template declares.
 *
 * <p>Without this, {@code engine.DAGUtility} merges a node's params over the template's
 * (ParameterUtil.addUniqueParams, keyed by exact name) with no key check at all - every merged
 * name becomes a {@code PARAM_<NAME>} env var on the TaskRun container, so a node param the
 * template never declared is both an undeclared injection point and a silently-swallowed typo.
 * This pins that the same exact-name match used by the merge is what rejects the save.
 */
class WorkflowTaskParamValidationTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "task-param-validation-ws";

  @Autowired private WorkflowService workflowService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    relationshipService.createNode(RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, java.util.Optional.empty());
    setFeatureSetting("workspaceQuotas", false);
  }

  @Test
  void aNodeParamNotDeclaredByTheTaskTemplateIsRejected() {
    String taskSlug = declaredParamTask("param-validation-undeclared", "greeting");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-undeclared-wf", taskSlug, new RunParam("shout", "loud"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));

    assertEquals("WORKFLOW_INVALID_TASK_PARAM", ex.getReason());
    assertTrue(
        List.of(ex.getArgs()).contains("[shout]"),
        "the exception must name the offending param");
  }

  @Test
  void nodeParamsThatAllMatchTheDeclaredSetSaveFine() {
    String taskSlug = declaredParamTask("param-validation-matching", "greeting");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-matching-wf", taskSlug, new RunParam("greeting", "hi"));

    Workflow saved = workflowService.create(WORKSPACE, workflow);

    assertEquals("param-validation-matching-wf", saved.getName());
  }

  @Test
  void aTemplateThatDeclaresNoParamsIsNotValidated() {
    // runworkflow/runscheduledworkflow-style system tasks take node params (e.g. "workflowRef")
    // their Template spec leaves undeclared - an empty declared set is "not modelled here", not
    // "no params allowed". A template with zero declared params exercises that same boundary.
    String taskSlug = noParamTask("param-validation-system");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-system-wf", taskSlug, new RunParam("workflowRef", "some-other-wf"));

    Workflow saved = workflowService.create(WORKSPACE, workflow);

    assertEquals("param-validation-system-wf", saved.getName());
  }

  @Test
  void updatingAWorkflowWithAnUndeclaredNodeParamIsAlsoRejected() {
    String taskSlug = declaredParamTask("param-validation-apply", "greeting");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-apply-wf", taskSlug, new RunParam("greeting", "hi"));
    workflowService.create(WORKSPACE, workflow);

    Workflow update =
        workflowWithTaskParams(
            "param-validation-apply-wf", taskSlug, new RunParam("greeting", "hi"),
            new RunParam("typoParam", "oops"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.apply(WORKSPACE, update, false));

    assertEquals("WORKFLOW_INVALID_TASK_PARAM", ex.getReason());
  }

  private static Workflow workflowWithTaskParams(
      String name, String taskSlug, RunParam... params) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);

    WorkflowTask work = new WorkflowTask();
    work.setName("work");
    work.setType(TaskType.template);
    work.setTaskRef(taskSlug);
    work.setParams(new LinkedList<>(List.of(params)));
    WorkflowTaskDependency dependsOnStart = new WorkflowTaskDependency();
    dependsOnStart.setTaskRef("start");
    work.setDependencies(new LinkedList<>(List.of(dependsOnStart)));

    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency dependsOnWork = new WorkflowTaskDependency();
    dependsOnWork.setTaskRef("work");
    end.setDependencies(new LinkedList<>(List.of(dependsOnWork)));

    workflow.setTasks(new LinkedList<>(List.of(start, work, end)));
    return workflow;
  }

  /** Creates a global Task Template (idempotently) declaring exactly one param: {@code paramName}. */
  private String declaredParamTask(String name, String paramName) {
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
    AbstractParam param = new AbstractParam();
    param.setName(paramName);
    param.setType("text");
    task.getSpec().setParams(new LinkedList<>(List.of(param)));
    taskService.createGlobal(task);
    return name;
  }

  /** Creates a global Task Template (idempotently) declaring no params at all. */
  private String noParamTask(String name) {
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
    taskService.createGlobal(task);
    return name;
  }
}
