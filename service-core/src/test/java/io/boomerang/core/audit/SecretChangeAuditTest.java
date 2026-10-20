package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.core.SettingsService;
import io.boomerang.core.SystemControllerV2;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.schedule.WorkspaceScheduleControllerV2;
import io.boomerang.workflow.ParameterControllerV2;
import io.boomerang.workflow.TaskControllerV2;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.WorkspaceWorkflowControllerV2;
import io.boomerang.workspace.WorkspaceControllerV2;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A write to a secret records, in its audit event, the names of the secrets it changed or cleared
 * and never a value: on global and workspace parameters, admin settings, workflows, catalogue
 * tasks and schedules. A kept secret is not named, and a failed write names nothing.
 */
class SecretChangeAuditTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String SYSTEM = "system";
  private static final String MARKER = DataAdapterUtil.REDACTED;

  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ParameterControllerV2 parameterController;
  @Autowired private WorkspaceControllerV2 workspaceController;
  @Autowired private SystemControllerV2 systemController;
  @Autowired private SettingsService settingsService;
  @Autowired private TaskControllerV2 taskController;
  @Autowired private WorkspaceWorkflowControllerV2 workflowController;
  @Autowired private WorkspaceScheduleControllerV2 scheduleController;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;

  private final List<String> settingsGroups = new ArrayList<>();

  @BeforeEach
  void captureOn() {
    setFeatureSetting("workspaceQuotas", false);
    SettingEntity existing = settingsRepository.findOneByKey("audit");
    if (existing != null) {
      settingsRepository.delete(existing);
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey("audit");
    settings.setName("Audit");
    settings.setConfig(List.of(config("enabled", "true", null), config("level", "WRITE", null)));
    settingsRepository.save(settings);
  }

  /** Leaves the shared database as the other test classes expect it: capture off. */
  @AfterEach
  void captureOff() {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
    settingsGroups.forEach(
        key -> {
          SettingEntity group = settingsRepository.findOneByKey(key);
          if (group != null) {
            settingsRepository.delete(group);
          }
        });
  }

  @Test
  void aGlobalParameterNamesTheSecretItSetsReplacesAndClears() {
    String name = unique("auditToken");
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("password");
    param.setValue("gp-secret-0001");
    parameterController.create(param);
    assertThat(detail(event("parameter", name, 1))).isEqualTo(changed(name));

    parameterController.patch(name, json("{\"description\": \"rotated\", \"value\": \"*****\"}"));
    assertThat(detail(event("parameter", name, 2))).as("a kept secret is not named").isNull();

    parameterController.patch(name, json("{\"value\": \"gp-secret-0002\"}"));
    assertThat(detail(event("parameter", name, 3))).isEqualTo(changed(name));

    parameterController.patch(name, json("{\"value\": \"\"}"));
    assertThat(detail(event("parameter", name, 4))).isEqualTo(cleared(name));

    parameterController.delete(name);
    AuditEventEntity deleted = event("parameter", name, 5);
    assertThat(deleted.getAction()).isEqualTo("DELETE");
    assertThat(deleted.getLevel()).isEqualTo("DESTRUCTIVE");

    assertNoValue(events("parameter", name), "gp-secret-0001", "gp-secret-0002");
  }

  @Test
  void aWorkspaceParameterPatchNamesTheSecretItChanged() {
    String workspace = unique("audit-ws");
    WorkspaceRequest create = new WorkspaceRequest();
    create.setName(workspace);
    create.setDisplayName(workspace);
    workspaceController.createWorkspace(create);
    event("workspace", workspace, 1);

    workspaceController.updateWorkspace(workspace, workspacePatch("ws-secret-0001"));
    assertThat(detail(event("workspace", workspace, 2))).isEqualTo(changed("dbPass"));

    workspaceController.updateWorkspace(workspace, workspacePatch(MARKER));
    assertThat(detail(event("workspace", workspace, 3))).isNull();

    workspaceController.updateWorkspace(workspace, workspacePatch(""));
    assertThat(detail(event("workspace", workspace, 4))).isEqualTo(cleared("dbPass"));

    workspaceController.deleteParameters(workspace, "dbPass");
    awaitEngine("workspace parameter delete audited")
        .until(
            () ->
                events("parameter", "dbPass").stream()
                    .anyMatch(
                        e -> workspace.equals(e.getWorkspaceId()) && "DELETE".equals(e.getAction())));

    assertNoValue(events("workspace", workspace), "ws-secret-0001");
  }

  @Test
  void aSettingsPatchNamesTheSecuredEntryItChanged() {
    String group = seedSettingsGroup("set-secret-0001");

    systemController.patchSettings(json("{\"" + group + "\": {\"audit.token\": \"set-secret-0002\"}}"));
    assertThat(detail(settingsEvent(group, 1))).isEqualTo(changed(group + ".audit.token"));

    systemController.patchSettings(
        json("{\"" + group + "\": {\"audit.token\": \"*****\", \"audit.channel\": \"#ops\"}}"));
    assertThat(detail(settingsEvent(group, 2))).as("a kept secret is not named").isNull();

    systemController.patchSettings(json("{\"" + group + "\": {\"audit.token\": null}}"));
    assertThat(detail(settingsEvent(group, 3))).isEqualTo(cleared(group + ".audit.token"));

    assertNoValue(settingsEvents(group), "set-secret-0001", "set-secret-0002");
  }

  @Test
  void aFailedWriteNamesNoSecret() {
    String group = seedSettingsGroup("set-secret-0003");

    // The first group's change is collected before the unknown group refuses the whole patch.
    assertThatThrownBy(
            () ->
                systemController.patchSettings(
                    json(
                        "{\""
                            + group
                            + "\": {\"audit.token\": \"set-secret-0004\"}, \"no-such-group\": {}}")))
        .isNotNull();

    AuditEventEntity failed = settingsEvent(group, 1);
    assertThat(failed.getOutcome()).isEqualTo("FAILED");
    assertThat(detail(failed)).isNull();
    assertNoValue(List.of(failed), "set-secret-0003", "set-secret-0004");
  }

  @Test
  void aCatalogueTaskAndAWorkflowNameTheSecretsTheyChange() {
    String taskName = unique("audit-task");
    taskController.create(secretTask(taskName, "task-secret-0001"));
    assertThat(detail(event("task", taskName, 1))).isEqualTo(changed("registryToken"));

    taskController.apply(taskName, secretTask(taskName, "task-secret-0002"), false);
    assertThat(detail(event("task", taskName, 2))).isEqualTo(changed("registryToken"));

    String workflowName = unique("audit-wf");
    workflowController.createWorkflow(
        SYSTEM, workflow(workflowName, taskName, "wf-secret-0001", "node-secret-0001"));
    assertThat(detail(event("workflow", workflowName, 1)))
        .isEqualTo(changed("params.dbPassword", "tasks.work.registryToken"));

    // The read sent back as is (markers) changes nothing; a new node value is named alone.
    workflowController.applyWorkflow(
        workflow(workflowName, taskName, MARKER, "node-secret-0002"), SYSTEM, false);
    assertThat(detail(event("workflow", workflowName, 2)))
        .isEqualTo(changed("tasks.work.registryToken"));

    workflowController.applyWorkflow(workflow(workflowName, taskName, "", MARKER), SYSTEM, false);
    assertThat(detail(event("workflow", workflowName, 3))).isEqualTo(cleared("params.dbPassword"));

    assertNoValue(
        events("task", taskName), "task-secret-0001", "task-secret-0002");
    assertNoValue(
        events("workflow", workflowName),
        "wf-secret-0001",
        "node-secret-0001",
        "node-secret-0002");
  }

  @Test
  void aScheduleNamesTheSecretItSetsAndClears() {
    String taskSlug = "audit-schedule-task";
    seedGlobalTask(taskSlug);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("audit-sched-ws-" + suffix);
    request.setDisplayName(request.getName());
    String workspace = workspaceService.create(request).getName();
    Workflow workflow = runnableWorkflow("audit-sched-wf-" + suffix, taskSlug);
    workflow.setParams(new LinkedList<>(List.of(declared("dbPass", "password", null))));
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setManual(new Trigger(true));
    triggers.setSchedule(new Trigger(true));
    workflow.setTriggers(triggers);
    String workflowName = workflowService.create(workspace, workflow).getName();

    WorkflowSchedule created =
        scheduleController.createSchedule(workspace, schedule(workflowName, "sch-secret-0001"));
    String id = created.getId();
    assertThat(detail(event("schedule", id, 1))).isEqualTo(changed("dbPass"));

    WorkflowSchedule kept = schedule(workflowName, MARKER);
    kept.setId(id);
    scheduleController.updateSchedule(kept, workspace);
    assertThat(detail(event("schedule", id, 2))).isNull();

    WorkflowSchedule blank = schedule(workflowName, "");
    blank.setId(id);
    scheduleController.updateSchedule(blank, workspace);
    assertThat(detail(event("schedule", id, 3))).isEqualTo(cleared("dbPass"));

    scheduleController.deleteSchedule(workspace, id);
    assertThat(event("schedule", id, 4).getAction()).isEqualTo("DELETE");

    assertNoValue(events("schedule", id), "sch-secret-0001");
  }

  // ── events ──────────────────────────────────────────────────────────────────────────────────

  private List<AuditEventEntity> events(String resourceType, String resourceId) {
    return auditEventRepository.findAll().stream()
        .filter(e -> resourceType.equals(e.getResourceType()) && resourceId.equals(e.getResourceId()))
        .sorted(Comparator.comparing(AuditEventEntity::getTime))
        .toList();
  }

  /** The n-th (from 1) event for the resource, once that many have been written. */
  private AuditEventEntity event(String resourceType, String resourceId, int nth) {
    awaitEngine(resourceType + " audit event " + nth)
        .until(() -> events(resourceType, resourceId).size() >= nth);
    return events(resourceType, resourceId).get(nth - 1);
  }

  private List<AuditEventEntity> settingsEvents(String group) {
    return events("settings", "settings").stream()
        .filter(e -> e.getResourceName() != null && e.getResourceName().contains(group))
        .toList();
  }

  private AuditEventEntity settingsEvent(String group, int nth) {
    awaitEngine("settings audit event " + nth).until(() -> settingsEvents(group).size() >= nth);
    return settingsEvents(group).get(nth - 1);
  }

  private static Object detail(AuditEventEntity event) {
    return (event.getPayload() == null) ? null : event.getPayload().get("detail");
  }

  private static Map<String, Object> changed(String... names) {
    return Map.of(SecretChangeHolder.CHANGED, List.of(names));
  }

  private static Map<String, Object> cleared(String... names) {
    return Map.of(SecretChangeHolder.CLEARED, List.of(names));
  }

  private static void assertNoValue(List<AuditEventEntity> events, String... secrets) {
    String json = MAPPER.writeValueAsString(events);
    for (String secret : secrets) {
      assertThat(json).doesNotContain(secret);
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────────────────────

  private String seedSettingsGroup(String secret) {
    String key = unique("audit-mask");
    SettingEntity entity = new SettingEntity();
    entity.setKey(key);
    entity.setName("Audit mask");
    entity.setType(ConfigurationType.ValuesList);
    entity.setConfig(
        new ArrayList<>(
            List.of(
                config("audit.token", secret, "secured"), config("audit.channel", "#general", "text"))));
    settingsService.updateSetting(entity);
    settingsGroups.add(key);
    return key;
  }

  private static WorkspaceRequest workspacePatch(String value) {
    WorkspaceRequest request = new WorkspaceRequest();
    AbstractParam param = declared("dbPass", "password", null);
    param.setValue(value);
    request.setParameters(new LinkedList<>(List.of(param)));
    return request;
  }

  private static Task secretTask(String name, String secretDefault) {
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.generic);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setScript("login $(params.registryToken)\n");
    task.getSpec()
        .setParams(new LinkedList<>(List.of(declared("registryToken", "password", secretDefault))));
    return task;
  }

  private static Workflow workflow(
      String name, String taskSlug, String defaultValue, String nodeToken) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setParams(new LinkedList<>(List.of(declared("dbPassword", "password", defaultValue))));
    WorkflowTask work = node("work", TaskType.generic, taskSlug, "start");
    work.setParams(new LinkedList<>(List.of(new RunParam("registryToken", nodeToken))));
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                node("start", TaskType.start, null, null),
                work,
                node("end", TaskType.end, null, "work"))));
    return workflow;
  }

  private static WorkflowSchedule schedule(String workflowName, String secret) {
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setName("nightly");
    schedule.setWorkflowRef(workflowName);
    schedule.setType(WorkflowScheduleType.runOnce);
    schedule.setStatus(WorkflowScheduleStatus.inactive);
    schedule.setDateSchedule(new Date(System.currentTimeMillis() + 86_400_000L));
    schedule.setTimezone("UTC");
    schedule.setParams(new LinkedList<>(List.of(new RunParam("dbPass", secret))));
    return schedule;
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

  private static AbstractParam declared(String name, String type, Object defaultValue) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setDefaultValue(defaultValue);
    return param;
  }

  private static SettingConfig config(String key, String value, String type) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    config.setType(type);
    return config;
  }

  private static JsonNode json(String body) {
    return MAPPER.readTree(body);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
