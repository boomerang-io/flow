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
 * Matching is CASE-INSENSITIVE (ruled 2026-08-26, the GitHub Actions model): a node param may
 * case-vary from its declared name, and in exchange, names that are case/separator variants of
 * each other are rejected as PARAM_NAME_COLLISION - lossy PARAM_<NAME> env mangling is only safe
 * when such duplicates cannot exist.
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

  /*
   * An empty (or absent) value is a valid value: emptiness can be meaningful to the workflow, and
   * a substitution can legitimately resolve to empty. The save accepts it and persists the
   * parameter as authored; a task that requires a value (runworkflow's workflowRef) fails its own
   * run at execution time with a message naming the parameter.
   */
  @Test
  void aNodeParamWithNoValueIsAcceptedOnATemplateThatDeclaresNoParams() {
    String taskSlug = noParamTask("param-validation-empty-value-undeclared");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-empty-value-undeclared-wf",
            taskSlug,
            new RunParam("workflowRef", null));

    Workflow created = workflowService.create(WORKSPACE, workflow);

    assertEquals("param-validation-empty-value-undeclared-wf", created.getName());
  }

  @Test
  void aNodeParamWithAnEmptyStringValueIsAcceptedAndSurvivesSave() {
    String taskSlug = declaredParamTask("param-validation-empty-value-declared", "greeting");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-empty-value-declared-wf",
            taskSlug,
            new RunParam("greeting", ""));

    Workflow created = workflowService.create(WORKSPACE, workflow);

    assertEquals(
        "",
        created.getTasks().stream()
            .filter(t -> !"start".equals(t.getName()) && !"end".equals(t.getName()))
            .findFirst()
            .orElseThrow()
            .getParams()
            .get(0)
            .getValue());
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

  @Test
  void aNodeParamThatCaseVariesFromTheDeclaredNameIsAccepted() {
    String taskSlug = declaredParamTask("param-validation-case", "githubToken");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-case-wf", taskSlug, new RunParam("GITHUBTOKEN", "ghp_x"));

    Workflow saved = workflowService.create(WORKSPACE, workflow);

    assertEquals("param-validation-case-wf", saved.getName());
  }

  @Test
  void caseOrSeparatorVariantNodeParamsAreRejected() {
    String taskSlug = noParamTask("param-validation-collision");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-collision-wf",
            taskSlug,
            new RunParam("my-param", "a"),
            new RunParam("my_param", "b"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));

    assertEquals("PARAM_NAME_COLLISION", ex.getReason());
  }

  @Test
  void aTemplateDeclaringCaseVariantParamsIsRejected() {
    Task task = new Task();
    task.setName("param-validation-template-collision");
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    AbstractParam upper = new AbstractParam();
    upper.setName("Token");
    upper.setType("text");
    AbstractParam lower = new AbstractParam();
    lower.setName("token");
    lower.setType("text");
    task.getSpec().setParams(new LinkedList<>(List.of(upper, lower)));

    BoomerangException ex =
        assertThrows(BoomerangException.class, () -> taskService.createGlobal(task));

    assertEquals("PARAM_NAME_COLLISION", ex.getReason());
  }

  @Test
  void aNodeParamWithADottedNameIsRejected() {
    String taskSlug = noParamTask("param-validation-dotted");
    Workflow workflow =
        workflowWithTaskParams(
            "param-validation-dotted-wf", taskSlug, new RunParam("my.param", "x"));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowService.create(WORKSPACE, workflow));

    assertEquals("PARAM_INVALID_NAME", ex.getReason());
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
