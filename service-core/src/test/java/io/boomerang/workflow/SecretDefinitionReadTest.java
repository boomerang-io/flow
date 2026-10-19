package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.workflow.model.Action;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.json.JsonMapper;

/**
 * Secrets are hidden on every API read of a definition - a catalogue task's password default, a
 * Workflow's password default and a task node's secret value - and kept when a client sends the
 * hidden read back. Each read is checked on its whole serialised form, not one field, and each
 * keep against the stored revision and the values a run then receives. The canvas reads are out
 * of scope and covered by {@link SecretsAtRestTest}.
 */
class SecretDefinitionReadTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM = "system";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private ActionService actionService;
  @Autowired private ActionRepository actionRepository;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void noQuotas() {
    setFeatureSetting("workspaceQuotas", false);
  }

  // ── catalogue tasks ──────────────────────────────────────────────────────────────────────────

  @Test
  void aCatalogueTaskPasswordDefaultIsHiddenOnEveryReadAndKeptOnApply() {
    String name = unique("read-mask-global");
    assertHidden(taskService.createGlobal(secretTask(name, "task-default-0001")), "task-default-0001");

    Task read = taskService.getGlobal(name, Optional.empty());
    assertHidden(read, "task-default-0001");
    assertEquals(DataAdapterUtil.REDACTED, declared(read, "registryToken").getDefaultValue());
    assertEquals("eu-west-1", declared(read, "region").getDefaultValue(), "a plain default shows");
    assertHidden(
        taskService.queryGlobal(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(List.of(name)))
            .getContent()
            .get(0),
        "task-default-0001");
    assertHidden(taskService.getAsTektonGlobal(name, Optional.empty()), "task-default-0001");

    // Applying the hidden read back - the marker, then no value - keeps the stored default.
    read.setDescription("edited");
    assertHidden(taskService.applyGlobal(name, read, false), "task-default-0001");
    assertEquals("task-default-0001", storedDefault(name));
    Task omitted = taskService.getGlobal(name, Optional.empty());
    declared(omitted, "registryToken").setDefaultValue(null);
    taskService.applyGlobal(name, omitted, false);
    assertEquals("task-default-0001", storedDefault(name));
    // The Tekton YAML round trip keeps it too.
    taskService.applyAsTektonGlobal(name, taskService.getAsTektonGlobal(name, Optional.empty()), false);
    assertEquals("task-default-0001", storedDefault(name));

    // A new value replaces it.
    Task replaced = taskService.getGlobal(name, Optional.empty());
    declared(replaced, "registryToken").setDefaultValue("task-default-0002");
    assertHidden(taskService.applyGlobal(name, replaced, false), "task-default-0002");
    assertEquals("task-default-0002", storedDefault(name));

    // The engine reads the real default: a node that sets no value runs with it.
    String workflow = unique("read-mask-default-wf");
    workflowService.create(SYSTEM, linearWorkflow(workflow, name, null));
    String wfRunId = submitAndAdmit(workflow);
    assertEquals("task-default-0002", taskParam(wfRunId, "registryToken"));

    // A blank clears it, and a cleared default reads as nothing.
    Task cleared = taskService.getGlobal(name, Optional.empty());
    declared(cleared, "registryToken").setDefaultValue("");
    taskService.applyGlobal(name, cleared, false);
    assertEquals("", storedDefault(name));
    assertEquals(
        null, declared(taskService.getGlobal(name, Optional.empty()), "registryToken").getDefaultValue());
  }

  @Test
  void aWorkspaceTaskPasswordDefaultIsHiddenOnEveryRead() {
    String name = unique("read-mask-ws");
    assertHidden(taskService.create(SYSTEM, secretTask(name, "ws-task-default-1")), "ws-task-default-1");
    assertHidden(taskService.get(SYSTEM, name, Optional.empty()), "ws-task-default-1");
    assertHidden(
        taskService.query(
            SYSTEM, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(List.of(name)))
            .getContent()
            .get(0),
        "ws-task-default-1");
    assertHidden(taskService.getAsTekton(SYSTEM, name, Optional.empty()), "ws-task-default-1");

    Task read = taskService.get(SYSTEM, name, Optional.empty());
    assertHidden(taskService.apply(name, SYSTEM, read, false), "ws-task-default-1");
    assertEquals("ws-task-default-1", storedDefault(RelationshipType.TEAMTASK, name));
  }

  // ── workflows ────────────────────────────────────────────────────────────────────────────────

  @Test
  void aWorkflowsSecretsAreHiddenOnEveryReadAndKeptWhenTheReadIsAppliedBack() throws Exception {
    String task = unique("read-mask-node-task");
    taskService.createGlobal(secretTask(task, "tmpl-default-0001"));
    String name = unique("read-mask-wf");
    Workflow created = workflowService.create(SYSTEM, linearWorkflow(name, task, "node-token-0001"));
    assertHidden(created, "wf-default-0001", "node-token-0001");

    Workflow read = workflowService.get(SYSTEM, name, Optional.empty(), true);
    assertHidden(read, "wf-default-0001", "node-token-0001");
    assertEquals(DataAdapterUtil.REDACTED, read.getParams().get(0).getDefaultValue());
    assertEquals(DataAdapterUtil.REDACTED, nodeParam(read, "registryToken").getValue());
    assertHidden(
        workflowService.query(
            SYSTEM, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(List.of(name)))
            .getContent()
            .get(0),
        "wf-default-0001", "node-token-0001");
    String exported =
        new String(
            workflowService.export(SYSTEM, name).getBody().getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
    assertThat(exported).doesNotContain("wf-default-0001", "node-token-0001");

    // Apply the hidden read back: both stored values survive, and a run still receives them.
    read.setDescription("edited");
    assertHidden(workflowService.apply(SYSTEM, read, false), "wf-default-0001", "node-token-0001");
    WorkflowRevisionEntity latest = latestRevision(name);
    assertEquals(2, latest.getVersion());
    assertEquals("wf-default-0001", latest.getParams().get(0).getDefaultValue());
    assertEquals("node-token-0001", nodeValue(latest, "registryToken"));
    String wfRunId = submitAndAdmit(name);
    assertEquals(
        "wf-default-0001",
        workflowRunRepository.findById(wfRunId).orElseThrow().getParams().stream()
            .filter(p -> "dbPassword".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue());
    assertEquals("node-token-0001", taskParam(wfRunId, "registryToken"));

    // A duplicate is copied from the stored values, not the hidden read.
    Workflow duplicate = workflowService.duplicate(SYSTEM, name);
    assertHidden(duplicate, "wf-default-0001", "node-token-0001");
    WorkflowRevisionEntity copied = latestRevision(duplicate.getName());
    assertEquals("wf-default-0001", copied.getParams().get(0).getDefaultValue());
    assertEquals("node-token-0001", nodeValue(copied, "registryToken"));

    // A blank clears both: the default is stored empty and the node keeps an explicit empty value.
    Workflow clearing = workflowService.get(SYSTEM, name, Optional.empty(), true);
    clearing.getParams().get(0).setDefaultValue("");
    nodeParam(clearing, "registryToken").setValue("");
    workflowService.apply(SYSTEM, clearing, false);
    WorkflowRevisionEntity cleared = latestRevision(name);
    assertEquals("", cleared.getParams().get(0).getDefaultValue());
    assertEquals("", nodeValue(cleared, "registryToken"));
  }

  // An import of an exported Workflow into a new one has nothing stored to keep: the hidden
  // workflow default is saved with no value and the marked node value is dropped, so the task's own
  // default applies on a run rather than the marker.
  @Test
  void aHiddenValueWithNothingStoredIsNeverSavedAsTheSecret() {
    String task = unique("read-mask-import-task");
    taskService.createGlobal(secretTask(task, "import-default-01"));
    String name = unique("read-mask-import-wf");
    Workflow imported = linearWorkflow(name, task, DataAdapterUtil.REDACTED);
    imported.getParams().get(0).setDefaultValue(DataAdapterUtil.REDACTED);
    workflowService.create(SYSTEM, imported);

    WorkflowRevisionEntity stored = latestRevision(name);
    assertEquals(null, stored.getParams().get(0).getDefaultValue());
    assertThat(node(stored).getParams()).noneMatch(p -> "registryToken".equals(p.getName()));
    String wfRunId = submitAndAdmit(name);
    assertEquals("import-default-01", taskParam(wfRunId, "registryToken"));
  }

  // A run form left blank sends no value; a client that echoes a read sends the marker. Either
  // leaves the declared secret default in place, and a blank runs with an empty value.
  @Test
  void aRunRequestSendingNoValueOrTheMarkerForASecretUsesItsDefaultAndABlankSetsItEmpty() {
    String task = unique("run-request-task");
    taskService.createGlobal(secretTask(task, "run-default-01"));
    String name = unique("run-request-wf");
    workflowService.create(SYSTEM, linearWorkflow(name, task, null));

    assertEquals("wf-default-0001", submittedParam(name, null));
    assertEquals("wf-default-0001", submittedParam(name, DataAdapterUtil.REDACTED));
    assertEquals("", submittedParam(name, ""));
    assertEquals("run-value-01", submittedParam(name, "run-value-01"));
  }

  private Object submittedParam(String workflowName, Object sent) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setParams(new LinkedList<>(List.of(new RunParam("dbPassword", sent))));
    String wfRunId = workflowService.submit(SYSTEM, workflowName, request, false).getId();
    return workflowRunRepository.findById(wfRunId).orElseThrow().getParams().stream()
        .filter(p -> "dbPassword".equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  // ── values that are only a reference ─────────────────────────────────────────────────────────

  @Test
  void aCatalogueDefaultThatIsOnlyAReferenceReadsAsWrittenAndOneInsideTextStaysHidden() {
    String lone = unique("read-ref-task");
    taskService.createGlobal(secretTask(lone, "$(global.params.registryToken)"));
    assertEquals(
        "$(global.params.registryToken)",
        declared(taskService.getGlobal(lone, Optional.empty()), "registryToken").getDefaultValue());

    String wrapped = unique("read-ref-wrapped-task");
    taskService.createGlobal(secretTask(wrapped, "Bearer $(params.token)"));
    assertEquals(
        DataAdapterUtil.REDACTED,
        declared(taskService.getGlobal(wrapped, Optional.empty()), "registryToken").getDefaultValue());
  }

  @Test
  void aNodeSecretThatIsOnlyAReferenceReadsAsWrittenRoundTripsAndResolvesHidden() throws Exception {
    String task = unique("read-ref-node-task");
    taskService.createGlobal(secretTask(task, "tmpl-default-0002"));
    String name = unique("read-ref-wf");
    Workflow definition = linearWorkflow(name, task, "$(workflow.params.dbPassword)");
    workflowService.create(SYSTEM, definition);

    Workflow read = workflowService.get(SYSTEM, name, Optional.empty(), true);
    RunParam token = nodeParam(read, "registryToken");
    assertEquals("$(workflow.params.dbPassword)", token.getValue());
    assertEquals(ParamType.secret, token.getType(), "the node param stays secret-typed");
    assertHidden(read, "wf-default-0001");

    // The read applied back stores the reference as sent.
    read.setDescription("edited");
    workflowService.apply(SYSTEM, read, false);
    assertEquals("$(workflow.params.dbPassword)", nodeValue(latestRevision(name), "registryToken"));

    // A run resolves it, and the resolved value is hidden on the run read.
    String wfRunId = submitAndAdmit(name);
    assertEquals("wf-default-0001", taskParam(wfRunId, "registryToken"));
    assertThat(
            MAPPER.writeValueAsString(workflowRunService.get(SYSTEM, wfRunId, true).getBody()))
        .doesNotContain("wf-default-0001");
  }

  @Test
  void aNodeSecretWithTextAroundAReferenceStaysHidden() {
    String task = unique("read-ref-text-task");
    taskService.createGlobal(secretTask(task, "tmpl-default-0003"));
    String name = unique("read-ref-text-wf");
    workflowService.create(SYSTEM, linearWorkflow(name, task, "Bearer $(workflow.params.dbPassword)"));

    Workflow read = workflowService.get(SYSTEM, name, Optional.empty(), true);
    assertEquals(DataAdapterUtil.REDACTED, nodeParam(read, "registryToken").getValue());
  }

  // ── run requests ─────────────────────────────────────────────────────────────────────────────

  @Test
  void aStartRequestWithANonStringSecretIsRejected() {
    String task = unique("read-mask-start-task");
    taskService.createGlobal(secretTask(task, "start-default-01"));
    String name = unique("read-mask-start-wf");
    workflowService.create(SYSTEM, linearWorkflow(name, task, null));
    String wfRunId =
        workflowService.submit(SYSTEM, name, new WorkflowSubmitRequest(), false).getId();

    WorkflowRunRequest request = new WorkflowRunRequest();
    request.setParams(
        new LinkedList<>(List.of(new RunParam("dbPassword", List.of("a", "b"), ParamType.secret))));
    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowRunService.start(wfRunId, Optional.of(request)));
    assertEquals(BoomerangError.PARAM_SECRET_NOT_STRING.getCode(), ex.getCode());
  }

  // ── manual action instructions ───────────────────────────────────────────────────────────────

  @Test
  void manualActionInstructionsNeverShowASecret() throws Exception {
    String task = unique("read-mask-action-task");
    taskService.createGlobal(secretTask(task, "action-default-1"));
    String name = unique("read-mask-action-wf");
    workflowService.create(SYSTEM, linearWorkflow(name, task, null));
    String workflowRef = workflowRef(name);

    // What TaskExecutionService.createActionTask stores: the resolved instructions param, here
    // tainted secret by the workflow password it takes in.
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setWorkflowRef(workflowRef);
    run.setStatus(RunStatus.waiting);
    run.setPhase(RunPhase.running);
    run.setCreationDate(new Date());
    run.setParams(
        new LinkedList<>(List.of(new RunParam("dbPassword", "action-secret-0001", ParamType.secret))));
    String wfRunId = workflowRunRepository.save(run).getId();
    String instructions = "Log in with action-secret-0001 and approve the change";
    TaskRunEntity taskRun = new TaskRunEntity();
    taskRun.setName("approve");
    taskRun.setType(TaskType.manual);
    taskRun.setWorkflowRef(workflowRef);
    taskRun.setWorkflowRunRef(wfRunId);
    taskRun.setParams(
        new LinkedList<>(List.of(new RunParam("instructions", instructions, ParamType.secret))));
    String taskRunId = taskRunRepository.save(taskRun).getId();
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflowRef);
    action.setWorkflowRunRef(wfRunId);
    action.setTaskRunRef(taskRunId);
    action.setType(ActionType.manual);
    action.setStatus(ActionStatus.submitted);
    action.setCreationDate(new Date());
    action.setInstructions(instructions);
    String actionId = actionRepository.save(action).getId();

    Action read = actionService.get(SYSTEM, actionId);
    assertThat(MAPPER.writeValueAsString(read)).doesNotContain("action-secret-0001");
    assertEquals("Log in with ***** and approve the change", read.getInstructions());
    assertEquals(
        instructions,
        actionRepository.findById(actionId).orElseThrow().getInstructions(),
        "the stored action is unchanged");
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  // A page is checked on its content: the test mapper has no Spring Data page serialiser.
  private static void assertHidden(Object response, String... secrets) {
    String json;
    try {
      json = MAPPER.writeValueAsString(response);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    assertThat(json).doesNotContain(secrets);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static Task secretTask(String name, String secretDefault) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.generic);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setScript("login $(params.registryToken)\n");
    task.getSpec()
        .setParams(
            new LinkedList<>(
                List.of(
                    param("registryToken", "password", secretDefault),
                    param("region", "text", "eu-west-1"))));
    return task;
  }

  private static Workflow linearWorkflow(String name, String taskSlug, String nodeToken) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setParams(new LinkedList<>(List.of(param("dbPassword", "password", "wf-default-0001"))));
    WorkflowTask work = node("work", TaskType.generic, taskSlug, "start");
    if (nodeToken != null) {
      work.setParams(new LinkedList<>(List.of(new RunParam("registryToken", nodeToken))));
    }
    workflow.setTasks(
        new LinkedList<>(
            List.of(node("start", TaskType.start, null, null), work, node("end", TaskType.end, null, "work"))));
    return workflow;
  }

  private String submitAndAdmit(String workflowName) {
    String wfRunId =
        workflowService.submit(SYSTEM, workflowName, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("generic TaskRun admitted")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, taskRun.getStatus());
            });
    return wfRunId;
  }

  private Object taskParam(String wfRunId, String name) {
    return taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow().getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no task run param named " + name))
        .getValue();
  }

  private Object storedDefault(String taskName) {
    return storedDefault(RelationshipType.TASK, taskName);
  }

  // The unscoped read the engine uses, which is not filtered.
  private Object storedDefault(RelationshipType type, String taskName) {
    String ref =
        type == RelationshipType.TASK
            ? relationshipService
                .filter(type, Optional.of(List.of(taskName)), Optional.empty(), Optional.empty(), false)
                .get(0)
            : relationshipService
                .filter(
                    type,
                    Optional.of(List.of(taskName)),
                    Optional.of(RelationshipType.WORKSPACE),
                    Optional.of(List.of(SYSTEM)),
                    false)
                .get(0);
    return declared(taskService.get(ref, Optional.empty()), "registryToken").getDefaultValue();
  }

  private String workflowRef(String name) {
    return relationshipService
        .filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(SYSTEM)),
            false)
        .get(0);
  }

  private WorkflowRevisionEntity latestRevision(String workflowName) {
    String ref = workflowRef(workflowName);
    WorkflowRevisionEntity latest =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(ref).orElseThrow();
    // On disk the node secret is stamped and sealed, never plaintext.
    Document raw =
        mongoTemplate.findOne(
            Query.query(Criteria.where("_id").is(latest.getId())),
            Document.class,
            mongoTemplate.getCollectionName(WorkflowRevisionEntity.class));
    assertThat(raw.toJson()).doesNotContain("node-token-0001", "wf-default-0001");
    return latest;
  }

  private static WorkflowTask node(WorkflowRevisionEntity revision) {
    return revision.getTasks().stream().filter(t -> "work".equals(t.getName())).findFirst().orElseThrow();
  }

  private static Object nodeValue(WorkflowRevisionEntity revision, String param) {
    return node(revision).getParams().stream()
        .filter(p -> param.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static RunParam nodeParam(Workflow workflow, String param) {
    return workflow.getTasks().stream()
        .filter(t -> "work".equals(t.getName()))
        .findFirst()
        .orElseThrow()
        .getParams()
        .stream()
        .filter(p -> param.equals(p.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static AbstractParam declared(Task task, String name) {
    return task.getSpec().getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static AbstractParam param(String name, String type, Object defaultValue) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setDefaultValue(defaultValue);
    return param;
  }

  private static WorkflowTask node(String name, TaskType type, String taskRef, String dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    if (dependsOn != null) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dependsOn);
      task.setDependencies(new LinkedList<>(List.of(dependency)));
    }
    return task;
  }
}
