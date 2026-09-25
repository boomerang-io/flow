package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A secret is a parameter type. A `password` field becomes a `secret` param, a string that takes
 * in a secret's value becomes one, and a run request may send one directly. Downward - on the
 * dispatcher's claim - the value is real and the type travels with it; upward - on the consumer
 * read - a secret-typed param reads as the redaction marker.
 *
 * <p>Uses the {@code generic} task type so the claim below takes only this class's task from the
 * shared queue.
 */
class SecretParamTypeTest extends AbstractEngineIntegrationTest {

  private static final String DB_PASSWORD = "s3cr3t-pa55w0rd";
  private static final String API_KEY = "literal-api-key-12345";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void seedGraphRoot() {
    seedRelationshipRoot();
  }

  @Test
  void aPasswordParamIsASecretOnTheClaimAndRedactedOnRead() {
    String taskId =
        catalogueTask(
            "secret-type-task",
            declared("apiKey", "password"),
            declared("connection", "text"),
            declared("greeting", "text"));

    Workflow workflow = new Workflow();
    workflow.setName("secret-type-claim");
    workflow.setParams(
        new LinkedList<>(
            List.of(
                withDefault(declared("dbPassword", "password"), DB_PASSWORD),
                withDefault(declared("dsn", "text"), "postgres://app:$(params.dbPassword)@db"))));
    WorkflowTask work = node("work", TaskType.generic, taskId, "start");
    work.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("apiKey", API_KEY),
                // Named apart from the workflow param: the task's own params are the innermost
                // layer, so $(params.dsn) inside a param named dsn would resolve against itself.
                new RunParam("connection", "$(params.dsn)"),
                new RunParam("greeting", "hello"))));
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null), work, node("end", TaskType.end, null, "work")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitEngine("generic TaskRun claimable")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, taskRun.getStatus());
              assertEquals(RunPhase.pending, taskRun.getPhase());
            });
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow().getId();

    // Workflow level: the password is a secret, and the string built from it is tainted.
    WorkflowRunEntity runEntity = workflowRunRepository.findById(wfRunId).orElseThrow();
    assertEquals(ParamType.secret, param(runEntity.getParams(), "dbPassword").getType());
    assertEquals(ParamType.secret, param(runEntity.getParams(), "dsn").getType());

    // Downward: the claim carries the real values and the type, on the wire.
    String agentId =
        dispatcherService.register(
            new DispatcherRegistrationRequest("secret-zone", "secret-zone.local", List.of("generic")));
    TaskRun claimed =
        dispatcherService.getTaskQueue(agentId).getBody().stream()
            .filter(t -> taskRunId.equals(t.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the generic dispatcher should claim the task"));
    assertEquals(API_KEY, param(claimed.getParams(), "apiKey").getValue());
    assertEquals(ParamType.secret, param(claimed.getParams(), "apiKey").getType());
    assertEquals(
        "postgres://app:" + DB_PASSWORD + "@db", param(claimed.getParams(), "connection").getValue());
    assertEquals(ParamType.secret, param(claimed.getParams(), "connection").getType(), "taint");
    assertEquals(ParamType.string, param(claimed.getParams(), "greeting").getType());

    JsonNode wire = objectMapper.valueToTree(claimed);
    boolean apiKeyIsSecretOnTheWire = false;
    for (JsonNode p : wire.get("params")) {
      if ("apiKey".equals(p.get("name").asString())) {
        apiKeyIsSecretOnTheWire = "secret".equals(p.get("type").asString());
      }
    }
    assertTrue(apiKeyIsSecretOnTheWire, "the claim payload must carry type: secret");

    // Stored: Spring Data maps the field directly, so the type is persisted.
    Document stored =
        mongoTemplate.findById(
            taskRunId, Document.class, mongoTemplate.getCollectionName(TaskRunEntity.class));
    boolean storedAsSecret =
        stored.getList("params", Document.class).stream()
            .anyMatch(d -> "apiKey".equals(d.get("name")) && "secret".equals(d.get("type")));
    assertTrue(storedAsSecret, "the TaskRun document must store type: secret");

    // Upward: every secret-typed param reads as the marker; a plain param is untouched.
    WorkflowRun display = workflowRunService.get(wfRunId, true);
    workflowRunService.filterSensitiveValues(display);
    assertEquals(DataAdapterUtil.REDACTED, param(display.getParams(), "dbPassword").getValue());
    assertEquals(DataAdapterUtil.REDACTED, param(display.getParams(), "dsn").getValue());
    TaskRun displayTask =
        display.getTasks().stream().filter(t -> taskRunId.equals(t.getId())).findFirst().orElseThrow();
    assertEquals(DataAdapterUtil.REDACTED, param(displayTask.getParams(), "apiKey").getValue());
    assertEquals(DataAdapterUtil.REDACTED, param(displayTask.getParams(), "connection").getValue());
    assertEquals("hello", param(displayTask.getParams(), "greeting").getValue());

    // The engine's own read keeps the real value.
    WorkflowRun unscoped = workflowRunService.get(wfRunId, true);
    assertEquals(DB_PASSWORD, param(unscoped.getParams(), "dbPassword").getValue());
  }

  @Test
  void aRunRequestMaySendASecretDirectly() {
    String workflowId = workflowWithStringParam("secret-type-explicit");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("note", "private-note-value", ParamType.secret),
                new RunParam("extra", "extra-secret-value", ParamType.secret))));

    String wfRunId = workflowService.submit(workflowId, request, false).getId();

    WorkflowRunEntity runEntity = workflowRunRepository.findById(wfRunId).orElseThrow();
    assertEquals(ParamType.secret, param(runEntity.getParams(), "note").getType(), "raised");
    assertEquals(ParamType.secret, param(runEntity.getParams(), "extra").getType());
    assertEquals("private-note-value", param(runEntity.getParams(), "note").getValue());

    WorkflowRun display = workflowRunService.get(wfRunId, false);
    workflowRunService.filterSensitiveValues(display);
    assertEquals(DataAdapterUtil.REDACTED, param(display.getParams(), "note").getValue());
    assertEquals(DataAdapterUtil.REDACTED, param(display.getParams(), "extra").getValue());
  }

  @Test
  void aNonStringSecretIsRejectedAtSubmit() {
    String workflowId = workflowWithStringParam("secret-type-non-string");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setParams(
        new LinkedList<>(List.of(new RunParam("note", List.of("a", "b"), ParamType.secret))));

    BoomerangException ex =
        assertThrows(
            BoomerangException.class, () -> workflowService.submit(workflowId, request, false));
    assertEquals(BoomerangError.PARAM_SECRET_NOT_STRING.getCode(), ex.getCode());
    assertEquals(400, ex.getStatus().value());
  }

  @Test
  void anUntypedParamStillBehavesAsAString() {
    String workflowId = workflowWithStringParam("secret-type-untyped");
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setParams(new LinkedList<>(List.of(new RunParam("note", "plain-value"))));

    String wfRunId = workflowService.submit(workflowId, request, false).getId();

    RunParam stored = param(workflowRunRepository.findById(wfRunId).orElseThrow().getParams(), "note");
    assertEquals(ParamType.string, stored.getType(), "the declared type stands");
    assertEquals("plain-value", stored.getValue());
    WorkflowRun display = workflowRunService.get(wfRunId, false);
    workflowRunService.filterSensitiveValues(display);
    assertEquals("plain-value", param(display.getParams(), "note").getValue());

    // On the wire an untyped param stays untyped - absent means string.
    assertNull(objectMapper.valueToTree(new RunParam("x", "y")).get("type"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String workflowWithStringParam(String name) {
    String taskId = catalogueTask(name + "-task", declared("greeting", "text"));
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setParams(new LinkedList<>(List.of(withDefault(declared("note", "text"), "default"))));
    WorkflowTask work = node("work", TaskType.generic, taskId, "start");
    work.setParams(new LinkedList<>(List.of(new RunParam("greeting", "hello"))));
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null), work, node("end", TaskType.end, null, "work")));
    return workflowService.create(workflow, false).getBody().getId();
  }

  private String catalogueTask(String name, AbstractParam... params) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.generic);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setParams(new LinkedList<>(List.of(params)));
    return taskService.create(task).getId();
  }

  private static AbstractParam declared(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }

  private static AbstractParam withDefault(AbstractParam param, String value) {
    param.setDefaultValue(value);
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

  private static RunParam param(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no param named " + name));
  }
}
