package io.boomerang.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A schedule's params arrive untyped from the UI and API. One the workflow declares as `password`
 * is stamped `secret` on save, so it is ciphertext at rest; every schedule response masks it; an
 * edit that sends the mask back keeps the stored value; and the scheduled run still receives it.
 */
class ScheduleSecretParamTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "schedule-secret-param-task";
  private static final String LABEL = "crypt_v1{AESGCM|";

  @Autowired private ScheduleService scheduleService;
  @Autowired private ScheduleJob scheduleJob;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowScheduleRepository scheduleRepository;
  @Autowired private MongoTemplate mongoTemplate;

  private String workspace;
  private String workflowName;

  @BeforeEach
  void workflowWithAPasswordParam() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("workspaceQuotas", false);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("schedule-secret-ws-" + suffix);
    request.setDisplayName(request.getName());
    workspace = workspaceService.create(request).getName();
    Workflow workflow = runnableWorkflow("schedule-secret-wf-" + suffix, TASK_SLUG);
    workflow.setParams(
        new LinkedList<>(List.of(declared("dbPass", "password"), declared("region", "text"))));
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    workflowName = workflowService.create(workspace, workflow).getName();
  }

  @Test
  void aUiCreatedSchedulesPasswordParamIsCiphertextAtRestAndMaskedOnEveryRead() {
    WorkflowSchedule created = scheduleService.create(workspace, schedule("sched-secret-0001"));

    Document raw = rawSchedule(created.getId());
    Document dbPass = param(raw, "dbPass");
    assertEquals("secret", dbPass.getString("type"));
    assertTrue(dbPass.getString("value").startsWith(LABEL), raw.toJson());
    assertFalse(raw.toJson().contains("sched-secret-0001"));
    assertEquals("eu-west-1", param(raw, "region").getString("value"), "non-secret stays plain");

    assertEquals(DataAdapterUtil.REDACTED, value(created.getParams(), "dbPass"));
    WorkflowSchedule read = scheduleService.get(workspace, created.getId());
    assertEquals(DataAdapterUtil.REDACTED, value(read.getParams(), "dbPass"));
    assertEquals("eu-west-1", value(read.getParams(), "region"));
    WorkflowSchedule listed =
        scheduleService
            .query(
                workspace,
                0,
                10,
                Sort.by("creationDate"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getContent()
            .stream()
            .filter(s -> created.getId().equals(s.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals(DataAdapterUtil.REDACTED, value(listed.getParams(), "dbPass"));
    assertEquals(
        "sched-secret-0001",
        value(scheduleService.internalGet(created.getId()).getParams(), "dbPass"),
        "the firing path reads the real value");
  }

  @Test
  void anEditSendingTheMaskBackKeepsTheStoredValueAndTheScheduledRunReceivesIt() {
    String id = scheduleService.create(workspace, schedule("sched-secret-0001")).getId();

    // What the form does: read, change another field, send everything back.
    WorkflowSchedule edit = scheduleService.get(workspace, id);
    edit.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("dbPass", DataAdapterUtil.REDACTED),
                new RunParam("region", "ap-southeast-2"))));
    WorkflowSchedule applied = scheduleService.apply(workspace, edit);

    assertEquals(DataAdapterUtil.REDACTED, value(applied.getParams(), "dbPass"));
    WorkflowScheduleEntity stored = scheduleRepository.findById(id).orElseThrow();
    assertEquals("sched-secret-0001", value(stored.getParams(), "dbPass"));
    assertEquals("ap-southeast-2", value(stored.getParams(), "region"));
    assertTrue(param(rawSchedule(id), "dbPass").getString("value").startsWith(LABEL));

    scheduleJob.execute(workspace, stored.getWorkflowRef(), id);
    WorkflowRunEntity run =
        mongoTemplate.findOne(
            Query.query(Criteria.where("initiatedByRef").is(id)), WorkflowRunEntity.class);
    assertEquals("sched-secret-0001", value(run.getParams(), "dbPass"));
    assertEquals(ParamType.secret, type(run.getParams(), "dbPass"));
  }

  @Test
  void anEditWithANewValueReplacesTheStoredSecret() {
    String id = scheduleService.create(workspace, schedule("sched-secret-0001")).getId();

    WorkflowSchedule edit = scheduleService.get(workspace, id);
    edit.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("dbPass", "sched-secret-0002"), new RunParam("region", "eu-west-1"))));
    scheduleService.apply(workspace, edit);

    WorkflowScheduleEntity stored = scheduleRepository.findById(id).orElseThrow();
    assertEquals("sched-secret-0002", value(stored.getParams(), "dbPass"));
    assertEquals(ParamType.secret, type(stored.getParams(), "dbPass"));
    assertFalse(rawSchedule(id).toJson().contains("sched-secret-0002"));
  }

  private WorkflowSchedule schedule(String secret) {
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setName("nightly");
    schedule.setWorkflowRef(workflowName);
    schedule.setType(WorkflowScheduleType.runOnce);
    schedule.setStatus(WorkflowScheduleStatus.inactive);
    schedule.setDateSchedule(new Date(System.currentTimeMillis() + 86_400_000L));
    schedule.setTimezone("UTC");
    // As the UI sends them: no type.
    schedule.setParams(
        new LinkedList<>(List.of(new RunParam("dbPass", secret), new RunParam("region", "eu-west-1"))));
    return schedule;
  }

  private Document rawSchedule(String id) {
    return mongoTemplate.findOne(
        Query.query(Criteria.where("_id").is(id)),
        Document.class,
        mongoTemplate.getCollectionName(WorkflowScheduleEntity.class));
  }

  private static Document param(Document schedule, String name) {
    return schedule.getList("params", Document.class).stream()
        .filter(p -> name.equals(p.getString("name")))
        .findFirst()
        .orElseThrow();
  }

  private static Object value(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static ParamType type(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getType();
  }

  private static AbstractParam declared(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }
}
