package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.LogCapture;
import io.boomerang.common.entity.TaskRunEntity;
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
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.ParameterService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Secret values never reach a log, even with every Boomerang logger at debug. A run whose task
 * takes secrets from every source - a global, workflow and catalogue password default, a node value
 * for a password field, a submit request and a start request - and whose script references them, is created,
 * submitted, started and claimed while every log line is captured; no captured line may hold any
 * of the values.
 *
 * <p>Uses the {@code generic} task type so the claim takes only this class's task from the shared
 * queue.
 */
class SecretLogLeakTest extends AbstractEngineIntegrationTest {

  private static final String WORKFLOW_DEFAULT = "wf-default-s3cr3t-1111";
  private static final String CATALOGUE_DEFAULT = "catalogue-default-s3cr3t-2222";
  private static final String NODE_VALUE = "node-value-s3cr3t-3333";
  private static final String REQUEST_VALUE = "run-request-s3cr3t-4444";
  private static final String START_VALUE = "start-request-s3cr3t-5555";
  private static final String GLOBAL_VALUE = "global-param-s3cr3t-6666";
  private static final List<String> SECRETS =
      List.of(
          WORKFLOW_DEFAULT, CATALOGUE_DEFAULT, NODE_VALUE, REQUEST_VALUE, START_VALUE, GLOBAL_VALUE);

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private ParameterService parameterService;
  @Autowired private TokenService tokenService;

  @Test
  void noSecretValueIsLoggedAtDebug() {
    List<String> lines;
    try (LogCapture logs =
        LogCapture.start(
            Level.DEBUG,
            "io.boomerang",
            "io.boomerang.core",
            "io.boomerang.workflow",
            "io.boomerang.engine",
            "io.boomerang.workspace",
            "io.boomerang.dispatcher",
            "io.boomerang.event",
            "io.boomerang.integrations",
            "io.boomerang.common")) {
      parameterService.create(withDefault(declared("leakGlobal", "password"), GLOBAL_VALUE));

      Task task = new Task();
      task.setName("secret-log-task");
      task.setType(TaskType.generic);
      task.getSpec().setImage("busybox:latest");
      task.getSpec()
          .setParams(
              new LinkedList<>(
                  List.of(
                      withDefault(declared("catalogueKey", "password"), CATALOGUE_DEFAULT),
                      declared("nodeKey", "password"),
                      declared("greeting", "text"))));
      task.getSpec()
          .setScript("echo $(params.catalogueKey) $(params.nodeKey) $(params.dbPassword)");
      String taskId = taskService.createGlobal(task).getName();

      Workflow workflow = new Workflow();
      workflow.setName("secret-log-leak");
      workflow.setParams(
          new LinkedList<>(
              List.of(
                  withDefault(declared("dbPassword", "password"), WORKFLOW_DEFAULT),
                  declared("requestKey", "text"),
                  declared("startKey", "password"))));
      WorkflowTask work = node("work", TaskType.generic, taskId, "start");
      work.setParams(
          new LinkedList<>(
              List.of(new RunParam("nodeKey", NODE_VALUE), new RunParam("greeting", "hello"))));
      workflow.setTasks(
          List.of(
              node("start", TaskType.start, null, null),
              work,
              node("end", TaskType.end, null, "work")));
      String workflowId = workflowService.create("system", workflow).getName();
      workflowService.get("system", workflowId, Optional.empty(), true);

      WorkflowSubmitRequest submit = new WorkflowSubmitRequest();
      submit.setParams(
          new LinkedList<>(List.of(new RunParam("requestKey", REQUEST_VALUE, ParamType.secret))));
      String wfRunId = workflowService.submit("system", workflowId, submit, false).getId();
      WorkflowRunRequest start = new WorkflowRunRequest();
      start.setParams(new LinkedList<>(List.of(new RunParam("startKey", START_VALUE))));
      workflowRunService.start(wfRunId, Optional.of(start));

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

      String agentId =
          dispatcherService.register(
              new DispatcherRegistrationRequest(
                  "secret-log-zone", "secret-log-zone.local", List.of("generic")));
      TaskRun claimed =
          dispatcherService.getTaskQueue(agentId, null, null, null, null).getBody().stream()
              .filter(t -> taskRunId.equals(t.getId()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the generic dispatcher should claim the task"));
      // The claim really carries the values, so the run exercised the paths that hold them.
      assertTrue(claimed.getSpec().getScript().contains(NODE_VALUE), "script filled in at claim");
      lines = logs.lines();
    } finally {
      parameterService.delete("leakGlobal");
    }

    assertFalse(lines.isEmpty(), "debug lines were captured");
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("Task List: ") && l.contains("work")),
        "the task list logs names");
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("Task Run Params: ") && l.contains("nodeKey")),
        "debug lines were captured, naming the params");
    for (String secret : SECRETS) {
      List<String> leaks = lines.stream().filter(l -> l.contains(secret)).toList();
      assertTrue(leaks.isEmpty(), "secret value logged: " + leaks);
    }
  }

  // A token's raw value exists only in the create response; the log names the token by id.
  @Test
  void aCreatedTokenIsNeverLoggedAtDebug() {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.key);
    request.setName("secret-log-token-" + UUID.randomUUID());
    request.setPrincipal("workflow-" + UUID.randomUUID());
    request.setPermissions(List.of("workflow/read"));
    TokenCreateResponse created;
    List<String> lines;
    try (LogCapture logs = LogCapture.start(Level.DEBUG, "io.boomerang", "io.boomerang.core")) {
      created = tokenService.create(request);
      lines = logs.lines();
    }

    assertTrue(
        lines.stream().anyMatch(l -> l.contains(created.getId())), "the token is logged by its id");
    List<String> leaks = lines.stream().filter(l -> l.contains(created.getToken())).toList();
    assertTrue(leaks.isEmpty(), "raw token logged: " + leaks);
    tokenService.delete(created.getId());
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
}
