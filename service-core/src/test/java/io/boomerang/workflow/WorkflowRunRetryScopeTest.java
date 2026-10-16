package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RetryScope;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunRetryRequest;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A scoped retry carries the origin's finished task runs over as copies and re-runs the rest. The
 * graph under test: start -> a -> b -> c -> end, with d beside a (start -> d -> b).
 */
class WorkflowRunRetryScopeTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "retry-scope-ws";

  @Autowired private TaskService taskService;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;

  @BeforeEach
  void seed() {
    setFeatureSetting("workspaceQuotas", false);
    relationshipService.createNode(RelationshipType.WORKSPACE, WORKSPACE, WORKSPACE, Optional.empty());
  }

  @Test
  void aRetryFromFailedCarriesTheFinishedTasksOverAndReRunsTheRest() {
    String workflowId = createWorkflow("retry-scope-failed");
    String originId = submit(workflowId);
    // a finished with a result, d failed, so b, c and end never ran.
    end(originId, "a", RunStatus.succeeded, new RunResult("out", "from-a"));
    end(originId, "d", RunStatus.failed, null);
    complete(originId, RunStatus.failed);

    WorkflowRun retried = workflowRunService.retry(WORKSPACE, originId, Optional.of(request(RetryScope.failed, List.of()))).getBody();

    Map<String, TaskRunEntity> tasks = tasksOf(retried.getId());
    Map<String, TaskRunEntity> originTasks = tasksOf(originId);
    assertEquals(originTasks.get("a").getId(), tasks.get("a").getReusedFromRef());
    assertEquals(RunStatus.succeeded, tasks.get("a").getStatus());
    assertEquals(RunPhase.completed, tasks.get("a").getPhase());
    assertEquals("from-a", tasks.get("a").getResults().get(0).getValue());
    assertEquals(originTasks.get("start").getId(), tasks.get("start").getReusedFromRef());
    for (String reRun : List.of("d", "b", "c", "end")) {
      assertNotNull(tasks.get(reRun), reRun + " must be in the new run's plan");
      assertNull(tasks.get(reRun).getReusedFromRef(), reRun + " must run again, not be copied");
      assertEquals(RunStatus.notstarted, tasks.get(reRun).getStatus());
    }
    assertEquals(RetryScope.failed, retried.getRetryFrom().getScope());
    assertEquals("retry", retried.getTrigger());
    assertEquals(originId, retried.getInitiatedByRef());
  }

  @Test
  void aRetryFromNamedTasksReRunsThemAndTheirDescendantsOnly() {
    String workflowId = createWorkflow("retry-scope-nodes");
    String originId = submit(workflowId);
    for (String name : List.of("a", "d", "b", "c", "end")) {
      end(originId, name, RunStatus.succeeded, null);
    }
    complete(originId, RunStatus.succeeded);

    WorkflowRunRetryRequest request = request(RetryScope.nodes, List.of("a"));
    request.getParams().add(new RunParam("model", "other"));
    request.getLabels().put("reason", "prompt-iteration");
    WorkflowRun retried = workflowRunService.retry(WORKSPACE, originId, Optional.of(request)).getBody();

    Map<String, TaskRunEntity> tasks = tasksOf(retried.getId());
    assertNotNull(tasks.get("d").getReusedFromRef(), "d is beside a, not after it");
    assertNotNull(tasks.get("start").getReusedFromRef());
    for (String reRun : List.of("a", "b", "c", "end")) {
      assertNull(tasks.get(reRun).getReusedFromRef(), reRun + " must run again");
    }
    assertEquals(List.of("a"), retried.getRetryFrom().getNodes());
    assertEquals("other", retried.getParams().stream().filter(p -> "model".equals(p.getName())).findFirst().orElseThrow().getValue());
    assertEquals("prompt-iteration", retried.getLabels().get("reason"));
  }

  @Test
  void aTaskNamedFailedIsATaskName() {
    String workflowId = createWorkflow("retry-scope-named-failed", "failed");
    String originId = submit(workflowId);
    for (String name : List.of("failed", "d", "b", "c", "end")) {
      end(originId, name, RunStatus.succeeded, null);
    }
    complete(originId, RunStatus.succeeded);

    WorkflowRun retried = workflowRunService.retry(WORKSPACE, originId, Optional.of(request(RetryScope.nodes, List.of("failed")))).getBody();

    Map<String, TaskRunEntity> tasks = tasksOf(retried.getId());
    assertNull(tasks.get("failed").getReusedFromRef(), "the task called failed re-runs");
    assertNotNull(tasks.get("d").getReusedFromRef());
    assertEquals(RetryScope.nodes, retried.getRetryFrom().getScope());
  }

  @Test
  void anUnknownTaskNameIsRefusedBeforeAnythingIsWritten() {
    String workflowId = createWorkflow("retry-scope-unknown");
    String originId = submit(workflowId);
    complete(originId, RunStatus.failed);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowRunService.retry(WORKSPACE, originId, Optional.of(request(RetryScope.nodes, List.of("chair")))));

    assertEquals("WORKFLOWRUN_RETRY_INVALID", ex.getReason());
    // The detail is the message argument the error catalogue formats into the response.
    assertTrue(String.valueOf(ex.getArgs()[0]).contains("chair"), String.valueOf(ex.getArgs()[0]));
    assertEquals(1, workflowRunRepository.findByWorkflowRefAndPhaseIn(workflowId, List.of(RunPhase.values())).size());
  }

  @Test
  void aRetryFromFailedOnARunWithNoFailureIsRefused() {
    String workflowId = createWorkflow("retry-scope-nothing-failed");
    String originId = submit(workflowId);
    for (String name : List.of("a", "d", "b", "c", "end")) {
      end(originId, name, RunStatus.succeeded, null);
    }
    complete(originId, RunStatus.succeeded);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowRunService.retry(WORKSPACE, originId, Optional.of(request(RetryScope.failed, List.of()))));

    assertEquals("WORKFLOWRUN_RETRY_INVALID", ex.getReason());
  }

  @Test
  void namesWithAnyOtherScopeAreRefused() {
    String workflowId = createWorkflow("retry-scope-stray-names");
    String originId = submit(workflowId);
    complete(originId, RunStatus.failed);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowRunService.retry(WORKSPACE, originId, Optional.of(request(RetryScope.all, List.of("a")))));

    assertEquals("WORKFLOWRUN_RETRY_INVALID", ex.getReason());
  }

  @Test
  void aScopedRetryOfAnActiveOriginCancelsTheOrigin() {
    String workflowId = createWorkflow("retry-scope-active");
    String originId = submit(workflowId);
    end(originId, "a", RunStatus.succeeded, null);
    end(originId, "d", RunStatus.succeeded, null);
    // b waits at a gate; the origin is still running.
    WorkflowRunEntity origin = workflowRunRepository.findById(originId).orElseThrow();
    origin.setStatus(RunStatus.waiting);
    origin.setPhase(RunPhase.running);
    workflowRunRepository.save(origin);

    WorkflowRun retried = workflowRunService.retry(WORKSPACE, originId, Optional.of(request(RetryScope.nodes, List.of("a")))).getBody();

    assertEquals(RunStatus.cancelled, workflowRunRepository.findById(originId).orElseThrow().getStatus());
    assertEquals(RunPhase.completed, workflowRunRepository.findById(originId).orElseThrow().getPhase());
    assertNotNull(tasksOf(retried.getId()).get("d").getReusedFromRef());
  }

  private static WorkflowRunRetryRequest request(RetryScope scope, List<String> nodes) {
    WorkflowRunRetryRequest request = new WorkflowRunRetryRequest();
    request.setScope(scope);
    request.getNodes().addAll(nodes);
    return request;
  }

  private Map<String, TaskRunEntity> tasksOf(String runId) {
    return taskRunRepository.findByWorkflowRunRefAndParentRefIsNull(runId).stream()
        .collect(Collectors.toMap(TaskRunEntity::getName, Function.identity()));
  }

  private void end(String runId, String name, RunStatus status, RunResult result) {
    TaskRunEntity task = taskRunRepository.findFirstByNameAndWorkflowRunRef(name, runId).orElseThrow();
    task.setStatus(status);
    task.setPhase(RunPhase.completed);
    if (result != null) {
      task.getResults().add(result);
    }
    taskRunRepository.save(task);
  }

  private void complete(String runId, RunStatus status) {
    WorkflowRunEntity run = workflowRunRepository.findById(runId).orElseThrow();
    run.setStatus(status);
    run.setPhase(RunPhase.completed);
    workflowRunRepository.save(run);
  }

  private String submit(String workflowId) {
    String runId = workflowService.submit(workflowId, new WorkflowSubmitRequest(), false).getId();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        WORKSPACE,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        runId,
        runId,
        Optional.empty(),
        Optional.empty());
    return runId;
  }

  private String createWorkflow(String name) {
    return createWorkflow(name, "a");
  }

  private String createWorkflow(String name, String firstTaskName) {
    Task template = new Task();
    template.setName(name + "-echo");
    template.setType(TaskType.template);
    template.getSpec().setImage("busybox:latest");
    template.getSpec().setCommand(List.of("echo"));
    String templateId = taskService.create(template).getId();

    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        List.of(
            workflowTask("start", TaskType.start, null),
            workflowTask(firstTaskName, TaskType.template, templateId, "start"),
            workflowTask("d", TaskType.template, templateId, "start"),
            workflowTask("b", TaskType.template, templateId, firstTaskName, "d"),
            workflowTask("c", TaskType.template, templateId, "b"),
            workflowTask("end", TaskType.end, null, "c")));
    String workflowId = workflowService.create(workflow, false).getBody().getId();
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        WORKSPACE,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflowId,
        workflowId,
        Optional.empty(),
        Optional.empty());
    return workflowId;
  }

  private static WorkflowTask workflowTask(String name, TaskType type, String taskRef, String... dependsOn) {
    WorkflowTask task = new WorkflowTask();
    task.setName(name);
    task.setType(type);
    task.setTaskRef(taskRef);
    for (String dep : dependsOn) {
      WorkflowTaskDependency dependency = new WorkflowTaskDependency();
      dependency.setTaskRef(dep);
      task.getDependencies().add(dependency);
    }
    return task;
  }
}
