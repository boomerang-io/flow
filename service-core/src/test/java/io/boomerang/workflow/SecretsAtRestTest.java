package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
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
import io.boomerang.core.SecretCipher;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.entity.GlobalParamEntity;
import io.boomerang.workflow.model.CanvasNode;
import io.boomerang.workflow.model.CanvasNodePosition;
import io.boomerang.workflow.model.WorkflowCanvas;
import io.boomerang.workflow.repository.GlobalParamRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;

/**
 * Secret parameter values are encrypted at rest and plain in memory, and a task run's stored spec
 * keeps its references until a dispatcher claims it. Each test reads the raw document beside the
 * entity, so what is on disk and what Flow hands out are checked together.
 *
 * <p>The claim tests narrow the poll to this class's own task slug, so a task another class left
 * in the shared queue is never taken here.
 */
class SecretsAtRestTest extends AbstractEngineIntegrationTest {

  private static final String LABEL = "crypt_v1{AESGCM|";
  private static final String SYSTEM = "system";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private ParameterService parameterService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private SecretCipher secretCipher;
  @Autowired private GlobalParamRepository globalParamRepository;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private TaskRevisionRepository taskRevisionRepository;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private WorkflowScheduleRepository scheduleRepository;
  @Autowired private IntegrationsRepository integrationsRepository;

  @BeforeEach
  void enableParameterLayers() {
    setFeatureSetting("globalParameters", true);
    setFeatureSetting("workspaceParameters", true);
    setFeatureSetting("workspaceQuotas", false);
  }

  // ── each stored secret is ciphertext on disk and plaintext in memory ─────────────────────────

  @Test
  void aPasswordGlobalParameterIsCiphertextAtRestAndBlankOnTheConsumerRead() {
    parameterService.create(declared("restGlobalToken", "password", null, "glob-secret-0001"));
    parameterService.create(declared("restGlobalRegion", "text", null, "ap-southeast-2"));

    Document stored = raw(GlobalParamEntity.class, Criteria.where("name").is("restGlobalToken"));
    assertTrue(stored.getString("value").startsWith(LABEL), stored.toJson());
    assertFalse(stored.toJson().contains("glob-secret-0001"));
    Document plain = raw(GlobalParamEntity.class, Criteria.where("name").is("restGlobalRegion"));
    assertEquals("ap-southeast-2", plain.getString("value"), "a non-secret value stays plaintext");

    assertEquals(
        "glob-secret-0001",
        globalParamRepository.findOneByName("restGlobalToken").orElseThrow().getValue());
    // The existing consumer filter: a password-typed definition's value is never returned.
    assertNull(
        parameterService.getAll().stream()
            .filter(p -> "restGlobalToken".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue());
  }

  @Test
  void aPasswordWorkspaceParameterIsCiphertextAtRest() {
    WorkspaceEntity workspace = new WorkspaceEntity();
    workspace.setName("secrets-at-rest-ws");
    workspace.setParameters(
        new LinkedList<>(
            List.of(
                declared("dbPass", "password", null, "ws-secret-0001"),
                declared("region", "text", null, "eu-west-1"))));
    workspaceRepository.save(workspace);

    Document stored = raw(WorkspaceEntity.class, Criteria.where("name").is("secrets-at-rest-ws"));
    List<Document> params = stored.getList("parameters", Document.class);
    assertTrue(param(params, "dbPass").getString("value").startsWith(LABEL));
    assertEquals("eu-west-1", param(params, "region").getString("value"));

    WorkspaceEntity read = workspaceRepository.findByNameIgnoreCase("secrets-at-rest-ws").orElseThrow();
    assertEquals(
        "ws-secret-0001",
        read.getParameters().stream()
            .filter(p -> "dbPass".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue());
  }

  @Test
  void aScheduleSecretParamIsCiphertextAtRest() {
    WorkflowScheduleEntity schedule = new WorkflowScheduleEntity();
    schedule.setName("secrets-at-rest-schedule");
    schedule.setWorkflowRef("secrets-at-rest-workflow");
    schedule.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("token", "sched-secret-0001", ParamType.secret),
                new RunParam("branch", "main", ParamType.string))));
    String id = scheduleRepository.save(schedule).getId();

    List<Document> params =
        raw(WorkflowScheduleEntity.class, Criteria.where("_id").is(id)).getList("params", Document.class);
    assertTrue(param(params, "token").getString("value").startsWith(LABEL));
    assertEquals("main", param(params, "branch").getString("value"));
    assertEquals(
        "sched-secret-0001",
        scheduleRepository.findById(id).orElseThrow().getParams().stream()
            .filter(p -> "token".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue());
  }

  // A value already carrying the label is stored as it is; a plaintext value written by an
  // earlier release loads unchanged.
  // The stored Slack OAuth response the Slack service reads its team token from.
  @Test
  void aStoredSlackTeamAuthKeepsItsTokensAndWebhookUrlAsCiphertextAtRest() {
    Map<String, Object> data = new HashMap<>();
    data.put("ok", true);
    data.put("accessToken", "xoxb-at-rest-0001");
    data.put("refreshToken", "xoxe-at-rest-0002");
    data.put("team", new HashMap<>(Map.of("id", "T-at-rest", "name", "At Rest")));
    data.put("authedUser", new HashMap<>(Map.of("id", "U-at-rest", "accessToken", "xoxp-at-rest-0003")));
    data.put(
        "incomingWebhook",
        new HashMap<>(
            Map.of("channel", "#general", "url", "https://hooks.slack.com/services/T/B/at-rest-0004")));
    IntegrationsEntity auth = new IntegrationsEntity();
    auth.setType("slack_auth");
    auth.setData(data);
    auth.setLabels(new HashMap<>(Map.of("teamId", "T-at-rest")));
    String id = integrationsRepository.save(auth).getId();

    Document stored = raw(IntegrationsEntity.class, Criteria.where("_id").is(id)).get("data", Document.class);
    assertTrue(stored.getString("accessToken").startsWith(LABEL), stored.toJson());
    assertTrue(stored.getString("refreshToken").startsWith(LABEL));
    assertTrue(stored.get("authedUser", Document.class).getString("accessToken").startsWith(LABEL));
    assertTrue(stored.get("incomingWebhook", Document.class).getString("url").startsWith(LABEL));
    assertFalse(stored.toJson().contains("at-rest-000"), stored.toJson());
    assertEquals("T-at-rest", stored.get("team", Document.class).getString("id"), "the rest stays plaintext");
    assertEquals("#general", stored.get("incomingWebhook", Document.class).getString("channel"));

    // The read the Slack service makes, by type and team label, hands back plaintext.
    Map<?, ?> loaded =
        (Map<?, ?>)
            integrationsRepository.findByType("slack_auth").stream()
                .filter(e -> "T-at-rest".equals(e.getLabels().get("teamId")))
                .findFirst()
                .orElseThrow()
                .getData();
    assertEquals("xoxb-at-rest-0001", loaded.get("accessToken"));
    assertEquals("xoxe-at-rest-0002", loaded.get("refreshToken"));
    assertEquals("xoxp-at-rest-0003", ((Map<?, ?>) loaded.get("authedUser")).get("accessToken"));
    assertEquals(
        "https://hooks.slack.com/services/T/B/at-rest-0004",
        ((Map<?, ?>) loaded.get("incomingWebhook")).get("url"));
  }

  @Test
  void aLabelledValueIsNotEncryptedTwiceAndALegacyPlaintextValueLoadsUnchanged() {
    String sealed = secretCipher.encrypt("pre-sealed-0001");
    GlobalParamEntity entity = new GlobalParamEntity();
    entity.setName("restPreSealed");
    entity.setType("password");
    entity.setValue(sealed);
    globalParamRepository.save(entity);
    assertEquals(
        sealed, raw(GlobalParamEntity.class, Criteria.where("name").is("restPreSealed")).getString("value"));
    assertEquals(
        "pre-sealed-0001",
        globalParamRepository.findOneByName("restPreSealed").orElseThrow().getValue());

    mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(GlobalParamEntity.class))
        .insertOne(
            new Document("name", "restLegacy").append("type", "password").append("value", "legacy-plain-1"));
    assertEquals(
        "legacy-plain-1", globalParamRepository.findOneByName("restLegacy").orElseThrow().getValue());
  }

  // ── the whole path: template, workflow, run, task run, claim ─────────────────────────────────

  @Test
  void aSecretTravelsEncryptedAtRestFilledInAtClaimAndMaskedOnRead() {
    Task template = secretTask("rest-registry-task");
    String slug = template.getName();
    parameterService.create(declared("restRegion", "text", null, "region-at-admission"));

    Workflow workflow = new Workflow();
    workflow.setName("rest-registry-workflow");
    workflow.setParams(
        new LinkedList<>(List.of(declared("dbPassword", "password", "wf-default-secret-1", null))));
    WorkflowTask work = node("work", TaskType.generic, slug, "start");
    work.setParams(new LinkedList<>(List.of(new RunParam("registryToken", "node-token-0001"))));
    workflow.setTasks(
        List.of(node("start", TaskType.start, null, null), work, node("end", TaskType.end, null, "work")));
    workflowService.create(SYSTEM, workflow);
    String workflowRef = workflowRef("rest-registry-workflow");

    // Task template revision: the password default is ciphertext.
    String taskRef =
        relationshipService
            .filter(RelationshipType.TASK, Optional.of(List.of(slug)), Optional.empty(), Optional.empty(), false)
            .get(0);
    Document taskRevision = raw(TaskRevisionEntity.class, Criteria.where("parentRef").is(taskRef));
    Document spec = taskRevision.get("spec", Document.class);
    assertTrue(
        param(spec.getList("params", Document.class), "registryToken").getString("defaultValue").startsWith(LABEL));
    assertEquals(
        "template-default-1",
        taskRevisionRepository.findByParentRefAndLatestVersion(taskRef).orElseThrow().getSpec().getParams().stream()
            .filter(p -> "registryToken".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getDefaultValue(),
        "the latest-version read is an aggregation, and still decrypts");

    // Workflow revision: the password default and the task-node value, stamped secret.
    Document revision = raw(WorkflowRevisionEntity.class, Criteria.where("workflowRef").is(workflowRef));
    assertTrue(
        param(revision.getList("params", Document.class), "dbPassword").getString("defaultValue").startsWith(LABEL));
    Document node = nodeDocument(revision, "work");
    Document nodeParam = param(node.getList("params", Document.class), "registryToken");
    assertEquals("secret", nodeParam.getString("type"), "stamped from the template's password type");
    assertTrue(nodeParam.getString("value").startsWith(LABEL));
    WorkflowRevisionEntity revisionEntity =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowRef).orElseThrow();
    assertEquals("node-token-0001", nodeValue(revisionEntity, "work", "registryToken"));

    // Run and task run.
    String wfRunId = workflowService.submit(SYSTEM, "rest-registry-workflow", new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitAdmitted(wfRunId);
    String taskRunId = taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow().getId();

    List<Document> runParams =
        raw(WorkflowRunEntity.class, Criteria.where("_id").is(wfRunId)).getList("params", Document.class);
    assertTrue(param(runParams, "dbPassword").getString("value").startsWith(LABEL));
    Document storedTask = raw(TaskRunEntity.class, Criteria.where("_id").is(taskRunId));
    Document storedToken = param(storedTask.getList("params", Document.class), "registryToken");
    assertTrue(storedToken.getString("value").startsWith(LABEL), "sealed by the admission write");
    // The stored spec holds the references, never the values.
    String storedScript = storedTask.get("spec", Document.class).getString("script");
    assertTrue(storedScript.contains("$(params.registryToken)"), storedScript);
    assertFalse(storedTask.toJson().contains("node-token-0001"), storedTask.toJson());
    assertEquals(
        "node-token-0001",
        taskRunRepository.findById(taskRunId).orElseThrow().getParams().stream()
            .filter(p -> "registryToken".equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue());

    // The consumer read masks the values and shows the spec as stored, with its references.
    WorkflowRun display = workflowRunService.get(wfRunId, true);
    workflowRunService.filterSensitiveValues(display, new HashMap<>());
    assertEquals(DataAdapterUtil.REDACTED, runParam(display.getParams(), "dbPassword").getValue());
    TaskRun displayTask =
        display.getTasks().stream().filter(t -> taskRunId.equals(t.getId())).findFirst().orElseThrow();
    assertEquals(DataAdapterUtil.REDACTED, runParam(displayTask.getParams(), "registryToken").getValue());
    assertTrue(displayTask.getSpec().getScript().contains("$(params.registryToken)"));
    assertFalse(displayTask.getSpec().getScript().contains("node-token-0001"));

    // An edit between admission and claim: a global reference written straight into the script
    // is read at claim (the accepted drift).
    AbstractParam region = declared("restRegion", "text", null, "region-at-claim");
    parameterService.update(region);

    String agent =
        dispatcherService.register(
            new DispatcherRegistrationRequest("rest-zone", "rest-zone.local", List.of("generic")));
    TaskRun claimed = claim(agent, slug, taskRunId);
    String script = claimed.getSpec().getScript();
    assertEquals(
        "login node-token-0001\nregion region-at-claim\nhost $(cat /etc/hostname)\n", script);
    assertEquals("node-token-0001", runParam(claimed.getParams(), "registryToken").getValue());
    // Claiming fills in a copy: the stored spec still holds the references.
    assertTrue(
        raw(TaskRunEntity.class, Criteria.where("_id").is(taskRunId))
            .get("spec", Document.class)
            .getString("script")
            .contains("$(params.registryToken)"));

    // A second claim of the same task run - as after a requeue - gets the same task param values.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("phase", RunPhase.pending).unset("claim.by"),
        TaskRunEntity.class);
    TaskRun reclaimed = claim(agent, slug, taskRunId);
    assertEquals(script, reclaimed.getSpec().getScript());
  }

  // The task-node param stamped on the canvas path, the one the workflow editor saves through.
  @Test
  void aTaskNodeValueSavedFromTheCanvasIsStampedSecretAndEncrypted() {
    String slug = secretTask("rest-canvas-task").getName();
    Workflow workflow = new Workflow();
    workflow.setName("rest-canvas-workflow");
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            node("work", TaskType.generic, slug, "start"),
            node("end", TaskType.end, null, "work")));
    workflowService.create(SYSTEM, workflow);

    WorkflowCanvas canvas = workflowService.composeGet(SYSTEM, "rest-canvas-workflow", Optional.empty());
    // A workflow saved through the API has no layout; the editor always sends one.
    canvas
        .getNodes()
        .forEach(
            n -> {
              if (n.getPosition() == null) {
                CanvasNodePosition position = new CanvasNodePosition();
                position.setX(0);
                position.setY(0);
                n.setPosition(position);
              }
            });
    CanvasNode work =
        canvas.getNodes().stream()
            .filter(n -> n.getData() != null && "work".equals(n.getData().getName()))
            .findFirst()
            .orElseThrow();
    work.getData().setParams(new LinkedList<>(List.of(new RunParam("registryToken", "canvas-token-01"))));
    workflowService.composeApply(SYSTEM, canvas, false);

    String workflowRef = workflowRef("rest-canvas-workflow");
    WorkflowRevisionEntity latest =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowRef).orElseThrow();
    Document revision = raw(WorkflowRevisionEntity.class, Criteria.where("_id").is(latest.getId()));
    Document nodeParam = param(nodeDocument(revision, "work").getList("params", Document.class), "registryToken");
    assertEquals("secret", nodeParam.getString("type"));
    assertTrue(nodeParam.getString("value").startsWith(LABEL));
    assertEquals("canvas-token-01", nodeValue(latest, "work", "registryToken"));
  }

  // Admission guarantees every reference resolves; a global deleted between admission and claim
  // is the one way a reference can fail at claim, and the claim then ends the task failed.
  @Test
  void aGlobalDeletedBetweenAdmissionAndClaimFailsTheClaim() {
    String slug = scriptTask("rest-gone-task", "region $(global.params.restGone)\n").getName();
    parameterService.create(declared("restGone", "text", null, "here-at-admission"));
    Workflow workflow = new Workflow();
    workflow.setName("rest-gone-workflow");
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            node("work", TaskType.generic, slug, "start"),
            node("end", TaskType.end, null, "work")));
    workflowService.create(SYSTEM, workflow);
    String wfRunId =
        workflowService.submit(SYSTEM, "rest-gone-workflow", new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());
    awaitAdmitted(wfRunId);
    String taskRunId = taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow().getId();

    parameterService.delete("restGone");
    String agent =
        dispatcherService.register(
            new DispatcherRegistrationRequest("rest-gone-zone", "rest-gone-zone.local", List.of("generic")));
    // Nothing is handed out, so the long poll would hold for its whole window: run it aside and
    // interrupt it once the task has ended.
    ExecutorService poller = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<TaskRun>>> poll =
          poller.submit(() -> dispatcherService.getTaskQueue(agent, 20, "generic", slug, null));
      awaitEngine("the claim to fail the task")
          .untilAsserted(
              () -> {
                TaskRunEntity after = taskRunRepository.findById(taskRunId).orElseThrow();
                assertEquals(RunStatus.failed, after.getStatus());
                assertEquals("SpecFillFailed", after.getStatusReason());
              });
      poller.shutdownNow();
      List<TaskRun> handedOut = poll.get(5, TimeUnit.SECONDS).getBody();
      assertTrue(
          handedOut == null || handedOut.stream().noneMatch(t -> taskRunId.equals(t.getId())),
          "a task whose spec cannot be filled in is not handed out");
    } catch (Exception e) {
      throw new AssertionError(e);
    } finally {
      poller.shutdownNow();
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  // A generic catalogue task whose password param reaches the script by reference, beside a
  // direct global reference and shell command substitution, which stays text.
  private Task secretTask(String name) {
    return scriptTask(
        name,
        "login $(params.registryToken)\nregion $(global.params.restRegion)\nhost $(cat /etc/hostname)\n");
  }

  private Task scriptTask(String name, String script) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.generic);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setScript(script);
    task.getSpec()
        .setParams(new LinkedList<>(List.of(declared("registryToken", "password", "template-default-1", null))));
    return taskService.createGlobal(task);
  }

  private TaskRun claim(String agent, String slug, String taskRunId) {
    return dispatcherService.getTaskQueue(agent, 20, "generic", slug, null).getBody().stream()
        .filter(t -> taskRunId.equals(t.getId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the dispatcher should claim the task"));
  }

  private void awaitAdmitted(String wfRunId) {
    awaitEngine("generic TaskRun admitted")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("work", wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, taskRun.getStatus());
              assertEquals(RunPhase.pending, taskRun.getPhase());
            });
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

  private Document raw(Class<?> entity, Criteria criteria) {
    Document document =
        mongoTemplate.findOne(Query.query(criteria), Document.class, mongoTemplate.getCollectionName(entity));
    assertTrue(document != null, "no stored " + entity.getSimpleName());
    return document;
  }

  private static Document nodeDocument(Document revision, String name) {
    return revision.getList("tasks", Document.class).stream()
        .filter(t -> name.equals(t.getString("name")))
        .findFirst()
        .orElseThrow();
  }

  private static Object nodeValue(WorkflowRevisionEntity revision, String task, String param) {
    return revision.getTasks().stream()
        .filter(t -> task.equals(t.getName()))
        .findFirst()
        .orElseThrow()
        .getParams()
        .stream()
        .filter(p -> param.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static Document param(List<Document> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getString("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no stored param named " + name));
  }

  private static RunParam runParam(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no param named " + name));
  }

  private static AbstractParam declared(String name, String type, Object defaultValue, Object value) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setDefaultValue(defaultValue);
    param.setValue(value);
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
