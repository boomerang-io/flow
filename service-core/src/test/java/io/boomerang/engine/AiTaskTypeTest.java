package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.workflow.WorkflowService;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The {@code ai} task type is dispatched, not executed inside the engine: it queues and parks
 * claimable exactly like {@code template}, {@code script}, {@code custom} and {@code generic}, and
 * the dispatcher that registered for it - and only that one - receives it. The catalogue task's
 * params are the whole authoring surface, so this also pins the two that carry risk: the
 * password-typed {@code token} reaches the claimant with its real value and is scrubbed out of the
 * consumer-facing model, and the six declared results come back on {@code end}.
 */
class AiTaskTypeTest extends AbstractEngineIntegrationTest {

  private static final String TOKEN_VALUE = "sk-ai-token-0123456789";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private DispatcherService dispatcherService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  @BeforeEach
  void seedGraphRoot() {
    seedRelationshipRoot();
  }

  @Test
  void aiNodeWaitsForAnAiDispatcherThenEndsWithItsResults() {
    String aiTaskId = aiCatalogueTask();

    // A workflow-level password param, referenced by the node: the shape the engine substitutes
    // and the shape the sensitive-value filter recognises (the revision's param spec is the type
    // authority - decision 0043).
    Workflow workflow = new Workflow();
    workflow.setName("ai-task-type-run");
    workflow.setParams(new LinkedList<>(List.of(password("token", TOKEN_VALUE))));
    WorkflowTask ask = node("ask", TaskType.ai, aiTaskId, "start");
    ask.setParams(
        new LinkedList<>(
            List.of(
                new RunParam("endpoint", "https://models.example.test/v1"),
                new RunParam("token", "$(params.token)"),
                new RunParam("model", "test-model"),
                new RunParam("prompt", "Summarise the release notes."))));
    workflow.setTasks(
        List.of(
            node("start", TaskType.start, null, null),
            ask,
            node("end", TaskType.end, null, "ask")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();

    String wfRunId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    workflowRunService.start(wfRunId, Optional.empty());

    // The ai node parks ready/pending: it is NOT auto-executed the way the engine-handled types
    // are, so it stays in the claim page until a dispatcher takes it.
    awaitEngine("ai TaskRun claimable")
        .untilAsserted(
            () -> {
              TaskRunEntity taskRun =
                  taskRunRepository.findFirstByNameAndWorkflowRunRef("ask", wfRunId).orElseThrow();
              assertEquals(RunStatus.ready, taskRun.getStatus());
              assertEquals(RunPhase.pending, taskRun.getPhase());
            });
    String taskRunId =
        taskRunRepository.findFirstByNameAndWorkflowRunRef("ask", wfRunId).orElseThrow().getId();

    // Claiming is by the dispatcher's registered types and nothing else: the ai page holds it,
    // the template page never sees it. Read-only, so no other test's queue is disturbed.
    assertTrue(
        containsId(taskRunService.findClaimable(List.of(TaskType.ai), 100), taskRunId),
        "an ai-registered dispatcher's claim page should hold the ai TaskRun");
    assertFalse(
        containsId(taskRunService.findClaimable(List.of(TaskType.template), 100), taskRunId),
        "a template-registered dispatcher's claim page should never hold an ai TaskRun");

    // The real claim, over the dispatcher protocol.
    String aiAgentId =
        dispatcherService.register(
            new DispatcherRegistrationRequest("ai-zone", "ai-zone.local", List.of("ai")));
    List<TaskRun> queue = dispatcherService.getTaskQueue(aiAgentId).getBody();
    assertTrue(
        queue != null && queue.stream().anyMatch(t -> taskRunId.equals(t.getId())),
        "the ai dispatcher's queue should carry the claimed ai TaskRun");

    // Downward the token is real - the pod cannot call the endpoint without it.
    assertEquals(
        TOKEN_VALUE,
        paramValue(taskRunRepository.findById(taskRunId).orElseThrow().getParams(), "token"),
        "the claimant must receive the resolved token");

    // Upward it is not. This is the call WorkflowRunService.filterSensitiveValues makes on the
    // workspace-scoped read, against the same revision param spec.
    WorkflowRun publicRun = workflowRunService.get(wfRunId, true);
    DataAdapterUtil.filterWorkflowRunValueByFieldType(
        publicRun,
        workflowRevisionRepository
            .findById(publicRun.getWorkflowRevisionRef())
            .orElseThrow()
            .getParams(),
        FieldType.PASSWORD.value());
    TaskRun publicTask =
        publicRun.getTasks().stream()
            .filter(t -> taskRunId.equals(t.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        DataAdapterUtil.REDACTED,
        paramValue(publicTask.getParams(), "token"),
        "the resolved token must not survive into a consumer-facing model");
    assertEquals("", paramValue(publicRun.getParams(), "token"));

    // End with the six declared results; the graph advance finishes the run.
    taskRunService.start(taskRunId, Optional.empty());
    awaitEngine("ai TaskRun running")
        .untilAsserted(
            () ->
                assertEquals(
                    RunStatus.running,
                    taskRunRepository.findById(taskRunId).orElseThrow().getStatus()));

    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.getResults()
        .addAll(
            List.of(
                result("output", "A three line summary."),
                result("promptTokens", "412"),
                result("completionTokens", "88"),
                result("totalTokens", "500"),
                result("finishReason", "stop"),
                result("model", "test-model")));
    taskRunService.end(taskRunId, Optional.of(end));

    awaitEngine("WorkflowRun succeeded and completed")
        .untilAsserted(
            () -> {
              WorkflowRunEntity run = workflowRunRepository.findById(wfRunId).orElseThrow();
              assertEquals(RunStatus.succeeded, run.getStatus());
              assertEquals(RunPhase.completed, run.getPhase());
            });

    TaskRunEntity completed = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunStatus.succeeded, completed.getStatus());
    assertEquals(RunPhase.completed, completed.getPhase());
    assertEquals("500", resultValue(completed.getResults(), "totalTokens"));
    assertEquals("A three line summary.", resultValue(completed.getResults(), "output"));
  }

  /** The catalogue entry, in the shape {@code seed/task-revisions.json} ships. */
  private String aiCatalogueTask() {
    Task task = new Task();
    task.setName("ai-task-type");
    task.setType(TaskType.ai);
    task.getSpec()
        .setParams(
            new LinkedList<>(
                List.of(
                    declared("endpoint", "text"),
                    declared("token", "password"),
                    declared("model", "text"),
                    declared("prompt", "texteditor::text"))));
    task.getSpec()
        .setResults(
            new LinkedList<>(
                List.of(
                    new ResultSpec("The model's response text", "output"),
                    new ResultSpec("Tokens consumed by the prompt", "promptTokens"),
                    new ResultSpec("Tokens generated in the response", "completionTokens"),
                    new ResultSpec("Prompt plus completion tokens", "totalTokens"),
                    new ResultSpec("Why the model stopped", "finishReason"),
                    new ResultSpec("The model the endpoint served", "model"))));
    return taskService.create(task).getId();
  }

  private static AbstractParam declared(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }

  private static AbstractParam password(String name, String value) {
    AbstractParam param = declared(name, "password");
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

  private static RunResult result(String name, String value) {
    RunResult runResult = new RunResult();
    runResult.setName(name);
    runResult.setValue(value);
    return runResult;
  }

  private static boolean containsId(List<TaskRunEntity> page, String id) {
    return page.stream().anyMatch(t -> id.equals(t.getId()));
  }

  private static Object paramValue(List<RunParam> params, String name) {
    return params.stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static Object resultValue(List<RunResult> results, String name) {
    return results.stream()
        .filter(r -> name.equals(r.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }
}
