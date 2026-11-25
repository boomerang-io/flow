package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.KeyValuePair;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * A workflow param's type is a field type. Save refuses one that names no field type; a param
 * with no type, or one stored before save refused it, runs as a string, so no stored workflow
 * makes a submit fail.
 */
class WorkflowParamTypeTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "system";

  private static final String TASK_SLUG = "param-type-test-task";

  @BeforeEach
  void aTaskToRun() {
    seedGlobalTask(TASK_SLUG);
  }

  private static final String MULTISELECT_TASK_SLUG = "param-type-multiselect-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private MongoTemplate mongoTemplate;

  // A multiselect param is an array of the chosen option keys: the node's value is saved as sent,
  // the task run carries it typed array, and a dispatcher's claim hands it out as a JSON array.
  @Test
  void aMultiselectNodeValueRunsAsAnArrayOfTheChosenKeys() {
    seedMultiselectTask();
    Workflow workflow = runnableWorkflow("param-type-multiselect-" + suffix(), MULTISELECT_TASK_SLUG);
    withWorkValue(workflow, List.of("read", "edit"));
    Workflow saved = workflowService.create(WORKSPACE, workflow);

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), true)
            .getId();

    RunParam admitted = param(admittedWork(runId).getParams(), "workspaceTools");
    assertThat(admitted.getType()).isEqualTo(ParamType.array);
    assertThat(admitted.getValue()).isEqualTo(List.of("read", "edit"));

    String dispatcher =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "multiselect-dispatcher", "multiselect.local", List.of("template")));
    TaskRun claimed =
        dispatcherService
            .getTaskQueue(dispatcher, null, null, MULTISELECT_TASK_SLUG, null)
            .getBody()
            .stream()
            .filter(t -> runId.equals(t.getWorkflowRunRef()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the poll did not hand out the task"));
    RunParam handedOut = param(claimed.getParams(), "workspaceTools");
    assertThat(handedOut.getType()).isEqualTo(ParamType.array);
    assertThat(handedOut.getValue()).isEqualTo(List.of("read", "edit"));
  }

  // A node that leaves a multiselect param out runs the task's default, an empty list.
  @Test
  void aMultiselectLeftOutRunsTheTasksEmptyDefault() {
    seedMultiselectTask();
    Workflow saved =
        workflowService.create(
            WORKSPACE,
            runnableWorkflow("param-type-multiselect-default-" + suffix(), MULTISELECT_TASK_SLUG));

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), true)
            .getId();

    RunParam admitted = param(admittedWork(runId).getParams(), "workspaceTools");
    assertThat(admitted.getType()).isEqualTo(ParamType.array);
    assertThat(admitted.getValue()).isEqualTo(List.of());
  }

  // A multiselect set to one reference naming nothing declared fails the task at admission.
  @Test
  void aMultiselectSetToAnUndeclaredReferenceFailsAdmission() {
    seedMultiselectTask();
    Workflow workflow =
        withWorkValue(
            runnableWorkflow("param-type-multiselect-unresolved-" + suffix(), MULTISELECT_TASK_SLUG),
            "$(workflow.params.nope)");
    Workflow saved = workflowService.create(WORKSPACE, workflow);

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), true)
            .getId();

    TaskRunEntity failed = endedWork(runId);
    assertThat(failed.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(failed.getStatusReason()).isEqualTo("ParamUnresolved");
    assertThat(failed.getStatusMessage()).contains("$(workflow.params.nope)");
  }

  // A secret referenced into a multiselect reaches the task; a run read scrubs it.
  @Test
  void aSecretInAMultiselectReachesTheTaskAndRunReadsScrubIt() {
    seedMultiselectTask();
    AbstractParam token = declared("token", "password");
    token.setDefaultValue("sk-array-secret-0001");
    Workflow workflow =
        withWorkValue(
            runnableWorkflow("param-type-multiselect-secret-" + suffix(), MULTISELECT_TASK_SLUG),
            "$(workflow.params.token)");
    workflow.setParams(new LinkedList<>(List.of(token)));
    Workflow saved = workflowService.create(WORKSPACE, workflow);

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), true)
            .getId();

    assertThat(param(admittedWork(runId).getParams(), "workspaceTools").getValue())
        .isEqualTo(List.of("sk-array-secret-0001"));
    TaskRun read =
        workflowRunService.get(WORKSPACE, runId, true).getBody().getTasks().stream()
            .filter(t -> "work".equals(t.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(param(read.getParams(), "workspaceTools").getValue()).isEqualTo("*****");
  }

  private static Workflow withWorkValue(Workflow workflow, Object value) {
    workflow.getTasks().stream()
        .filter(task -> "work".equals(task.getName()))
        .findFirst()
        .orElseThrow()
        .setParams(new LinkedList<>(List.of(new RunParam("workspaceTools", value))));
    return workflow;
  }

  // The work task once it has ended.
  private TaskRunEntity endedWork(String runId) {
    return awaitEngine("the work task to end")
        .until(
            () ->
                taskRunRepository.findByWorkflowRunRef(runId).stream()
                    .filter(t -> "work".equals(t.getName()))
                    .filter(t -> RunPhase.completed.equals(t.getPhase()))
                    .findFirst()
                    .orElse(null),
            Objects::nonNull);
  }

  private void seedMultiselectTask() {
    if (relationshipService.doesSlugOrRefExistForType(
        RelationshipType.TASK, MULTISELECT_TASK_SLUG)) {
      return;
    }
    AbstractParam tools = new AbstractParam();
    tools.setName("workspaceTools");
    tools.setLabel("Workspace Tools");
    tools.setType("multiselect");
    tools.setDefaultValue(List.of());
    tools.setOptions(
        Stream.of("read", "edit", "write", "bash")
            .map(
                key -> {
                  KeyValuePair option = new KeyValuePair();
                  option.setKey(key);
                  option.setValue(key);
                  return option;
                })
            .collect(Collectors.toList()));
    Task task = new Task();
    task.setName(MULTISELECT_TASK_SLUG);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    task.getSpec().setParams(new LinkedList<>(List.of(tools)));
    Task created = taskService.createGlobal(task);
    assertThat(created.getSpec().getParams().get(0).getType()).isEqualTo("multiselect");
  }

  // The work task once admission has resolved its params.
  private TaskRunEntity admittedWork(String runId) {
    return awaitEngine("the work task to be admitted")
        .until(
            () ->
                taskRunRepository.findByWorkflowRunRef(runId).stream()
                    .filter(t -> "work".equals(t.getName()))
                    .filter(t -> RunStatus.ready.equals(t.getStatus()))
                    .findFirst()
                    .orElse(null),
            Objects::nonNull);
  }

  private static RunParam param(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no param named " + name));
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void aTypeThatNamesNoFieldTypeIsRefusedAtSave() {
    Workflow workflow = workflow("param-type-unknown", declared("region", "string"));

    assertThatThrownBy(() -> workflowService.create(WORKSPACE, workflow))
        .isInstanceOf(BoomerangException.class)
        .satisfies(
            ex -> {
              BoomerangException refused = (BoomerangException) ex;
              assertThat(refused.getReason())
                  .isEqualTo(BoomerangError.PARAM_INVALID_TYPE.getReason());
              assertThat(refused.getStatus().value()).isEqualTo(400);
              assertThat(refused.getArgs()[0]).isEqualTo("string");
              assertThat((String) refused.getArgs()[1]).contains("text", "password", "multiselect");
              assertThat(refused.getArgs()[2]).isEqualTo("text");
            });
  }

  @Test
  void aParamWithNoTypeSavesAndSubmitsAsAString() {
    Workflow saved =
        workflowService.create(WORKSPACE, workflow("param-type-absent", declared("region", null)));

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), false)
            .getId();

    assertThat(runParam(runId, "region").getType()).isEqualTo(ParamType.string);
  }

  @Test
  void aStoredParamWhoseTypeNamesNoFieldTypeSubmitsAsAString() {
    Workflow saved =
        workflowService.create(
            WORKSPACE, workflow("param-type-stored", declared("region", "text")));
    String workflowRef =
        relationshipService
            .refsInWorkspace(
                RelationshipType.WORKFLOW, WORKSPACE, Optional.of(List.of(saved.getName())))
            .get(0);
    // A revision saved before save refused unknown types.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("workflowRef").is(workflowRef)),
        new Update().set("params.0.type", "string"),
        WorkflowRevisionEntity.class);

    String runId =
        workflowService
            .submit(WORKSPACE, saved.getName(), new WorkflowSubmitRequest(), false)
            .getId();

    assertThat(runParam(runId, "region").getType()).isEqualTo(ParamType.string);
  }

  private RunParam runParam(String runId, String name) {
    return workflowRunRepository.findById(runId).orElseThrow().getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no param named " + name));
  }

  private static Workflow workflow(String name, AbstractParam param) {
    Workflow workflow = new Workflow();
    workflow.setName(name + "-" + UUID.randomUUID().toString().substring(0, 8));
    workflow.setParams(new LinkedList<>(List.of(param)));
    WorkflowTask start = new WorkflowTask();
    start.setName("start");
    start.setType(TaskType.start);
    WorkflowTask end = new WorkflowTask();
    end.setName("end");
    end.setType(TaskType.end);
    WorkflowTaskDependency afterStart = new WorkflowTaskDependency();
    afterStart.setTaskRef("work");
    end.setDependencies(new LinkedList<>(List.of(afterStart)));
    // One task between start and end: a workflow with nothing between them is refused at submit.
    WorkflowTask work = new WorkflowTask();
    work.setName("work");
    work.setType(TaskType.template);
    work.setTaskRef(TASK_SLUG);
    WorkflowTaskDependency afterStartForWork = new WorkflowTaskDependency();
    afterStartForWork.setTaskRef("start");
    work.setDependencies(new LinkedList<>(List.of(afterStartForWork)));
    workflow.setTasks(new LinkedList<>(List.of(start, work, end)));
    return workflow;
  }

  private static AbstractParam declared(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setDefaultValue("eu");
    return param;
  }
}
