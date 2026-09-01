package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.workflow.TaskService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.SettingsRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full engine Spring context against a single static Testcontainers MongoDB. All
 * subclasses share one cached context and one database: tests must create their own data and
 * assert on their own ids, never on global collection state. Externals are neutralised (no
 * CloudEvents egress, no audit) so nothing else needs to run.
 */
@SpringBootTest
public abstract class AbstractEngineIntegrationTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  static {
    MONGO.start();
  }

  @DynamicPropertySource
  static void engineTestProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("boomerang"));
    registry.add("flow.mongo.collection.prefix", () -> "flowtest");
    registry.add("flow.events.sink.enabled", () -> "false");
    registry.add("flow.audit.enabled", () -> "false");
    // Watcher sweeps are exercised deterministically by direct invocation, not on a schedule.
    registry.add("flow.watcher.enabled", () -> "false");
  }

  @Autowired protected TaskRunRepository taskRunRepository;
  @Autowired protected WorkflowRunRepository workflowRunRepository;
  @Autowired protected TaskRunService taskRunService;
  @Autowired protected RelationshipService relationshipService;
  @Autowired protected SettingsRepository settingsRepository;
  @Autowired protected TaskService taskService;

  /**
   * Establishes an identity for every test, mirroring production: a served request always has one
   * (AuthenticationFilter when {@code flow.security.enabled=true}, otherwise
   * UnauthenticatedGlobalAuthenticationFilter), and background work hoists its own (ScheduleJob).
   *
   * <p>These tests call authz-scoped services (RelationshipService.filter/check,
   * WorkspaceWorkflowService.count) DIRECTLY, bypassing the servlet chain that would normally
   * establish it - so without this they run under a condition production never produces. Subclasses
   * that need a specific principal still override it in their own {@code @BeforeEach}, which JUnit
   * runs after this one.
   */
  @BeforeEach
  void establishTestIdentity() {
    Token principal = new Token(AuthScope.global);
    principal.setPrincipal("integration-test-principal");
    // Enforcement is real (no shadow mode): a global caller in production always carries a
    // resolved grant set - UnauthenticatedGlobalToken's **/** or a minted token's resolution -
    // so a grantless token here would be a condition production never produces.
    principal.setPermissions(
        java.util.List.of(
            new io.boomerang.core.security.model.ResolvedPermissions(
                io.boomerang.core.security.enums.PermissionScope.global,
                "**",
                java.util.List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal.getPrincipal(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void clearTestIdentity() {
    SecurityContextHolder.clearContext();
  }

  // Every workspace/global-task creation anchors on the root relationship node - a fresh install
  // seeds it via the loader, but this shared Testcontainers Mongo starts empty. The node id is
  // deterministic ("root:root"), so calling this from more than one test class is a no-op past
  // the first.
  protected void seedRelationshipRoot() {
    relationshipService.createNode(RelationshipType.ROOT, "root", "root", Optional.empty());
  }

  // WorkspaceService.setDefaultQuotas reads the "workspaces" settings document the loader normally
  // seeds. Mirrors the shipped default quota values (seed/settings.json) so a workspace-creating
  // test does not need its own copy.
  protected void seedTeamQuotaSettings() {
    if (settingsRepository.findOneByKey(WorkspaceService.WORKSPACES_SETTINGS_KEY) != null) {
      return;
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey(WorkspaceService.WORKSPACES_SETTINGS_KEY);
    settings.setName("Workspace Quotas");
    settings.setConfig(
        List.of(
            quotaConfig("max.workflowrun.concurrent", "4"),
            quotaConfig("max.workflow.count", "10"),
            quotaConfig("max.workflowrun.monthly", "20"),
            quotaConfig("max.workflowrun.duration", "30"),
            quotaConfig("max.workflow.storage", "25Gi"),
            quotaConfig("max.workflowrun.storage", "2Gi")));
    settingsRepository.save(settings);
  }

  private static SettingConfig quotaConfig(String key, String value) {
    return settingConfig(key, "number", value);
  }

  // WorkflowService.internalSubmit stamps the boomerang.io/task-* execution annotations
  // off the "task" settings document the loader normally seeds. Mirrors seed/settings.json.
  protected void seedTaskSettings() {
    if (settingsRepository.findOneByKey("task") != null) {
      return;
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey("task");
    settings.setName("Task");
    settings.setConfig(
        List.of(
            settingConfig("debug", "boolean", "false"),
            settingConfig("default.image", "string", "boomerangio/worker-flow:2.11.15"),
            settingConfig("deletion.policy", "string", "Never"),
            settingConfig("default.timeout", "number", "90")));
    settingsRepository.save(settings);
  }

  /**
   * Sets one key in the "features" settings document, creating the document or the key as needed.
   * Merges rather than replaces - the shared Testcontainers Mongo means several test classes seed
   * their own keys into this one document.
   */
  protected void setFeatureSetting(String key, boolean value) {
    SettingEntity settings = settingsRepository.findOneByKey("features");
    if (settings == null) {
      settings = new SettingEntity();
      settings.setKey("features");
      settings.setName("Features");
      settings.setConfig(new ArrayList<>());
    }
    List<SettingConfig> config = new ArrayList<>(settings.getConfig());
    config.stream()
        .filter(c -> key.equals(c.getKey()))
        .findFirst()
        .ifPresentOrElse(
            c -> c.setValue(Boolean.toString(value)),
            () -> config.add(settingConfig(key, "boolean", Boolean.toString(value))));
    settings.setConfig(config);
    settingsRepository.save(settings);
  }

  private static SettingConfig settingConfig(String key, String type, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setType(type);
    config.setValue(value);
    return config;
  }

  /**
   * Creates a global template Task (idempotently) so workflows have something real to reference:
   * DAGUtility.createTaskList resolves every non-start/end task against the catalogue. Needs the
   * root relationship node, so call {@link #seedRelationshipRoot()} first. Both the existence
   * filter and the changelog author read the current identity, which {@link
   * #establishTestIdentity()} has already put in place - this method no longer installs (and then
   * clears) one of its own, which used to leave the filter call above running with none.
   */
  protected void seedGlobalTask(String name) {
    if (!relationshipService
        .filter(RelationshipType.TASK, Optional.of(List.of(name)))
        .isEmpty()) {
      return;
    }
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setCommand(List.of("echo"));
    taskService.createGlobal(task);
  }

  /**
   * A Workflow that actually passes DAGUtility.validateWorkflow: start -> one task -> end. A
   * Workflow carrying only the implicit start/end pair is rejected as "incomplete, or invalid".
   */
  protected static Workflow runnableWorkflow(String name, String taskSlug) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                workflowTask("start", TaskType.start, null, null),
                workflowTask("work", TaskType.template, taskSlug, "start"),
                workflowTask("end", TaskType.end, null, "work"))));
    return workflow;
  }

  private static WorkflowTask workflowTask(
      String name, TaskType type, String taskRef, String dependsOn) {
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

  protected static ConditionFactory awaitEngine(String alias) {
    return Awaitility.await(alias)
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(250));
  }

  protected WorkflowRunEntity savedWorkflowRun(
      String workflowRef, RunStatus status, RunPhase phase) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setWorkflowRef(workflowRef);
    run.setStatus(status);
    run.setPhase(phase);
    run.setCreationDate(new Date());
    run.setStartTime(new Date());
    return workflowRunRepository.save(run);
  }

  protected TaskRunEntity savedTaskRun(
      String name,
      TaskType type,
      RunStatus status,
      RunPhase phase,
      String workflowRef,
      String workflowRunRef) {
    TaskRunEntity run = new TaskRunEntity();
    run.setName(name);
    run.setType(type);
    run.setStatus(status);
    run.setPhase(phase);
    run.setCreationDate(new Date());
    run.setWorkflowRef(workflowRef);
    run.setWorkflowRunRef(workflowRunRef);
    run.setDependencies(new LinkedList<>());
    return taskRunRepository.save(run);
  }
}
