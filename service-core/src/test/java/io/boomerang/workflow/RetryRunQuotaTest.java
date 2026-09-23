package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.WorkflowExecutionService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A retry creates a new run, so it has to clear the same limits a submit clears - it used to be
 * the one way past the workspace's quotas, and the one run whose timeout could outlive a ceiling
 * the operator had since lowered. The floor is not re-checked: a retry re-runs the same revision
 * with the same request, so the critical path it has to clear cannot have moved.
 */
class RetryRunQuotaTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";
  private static final String TASK_SLUG = "retry-quota-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowExecutionService workflowExecutionService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);
    seedGlobalTask(TASK_SLUG);
  }

  @AfterEach
  void resetQuotaFeature() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void aRetryAtTheConcurrentRunLimitIsRefused() {
    String workspace = createWorkspace("retry-quota-concurrent", quotasWithConcurrentRuns(0));
    workflowService.create(workspace, workflow("retry-quota-concurrent-workflow"));
    WorkflowRun source =
        workflowService.submit(workspace, "retry-quota-concurrent-workflow", request(), false);

    setFeatureSetting(QUOTA_FEATURE, true);

    BoomerangException ex =
        assertThrows(
            BoomerangException.class,
            () -> workflowRunService.retry(workspace, source.getId()));

    assertEquals("QUOTA_EXCEEDED", ex.getReason());
    assertEquals(1, runsOf(source.getWorkflowRef()).size(), "the refused retry created no clone");
  }

  @Test
  void aRetryAfterTheCeilingWasLoweredGetsTheLoweredTimeout() {
    String workspace = createWorkspace("retry-quota-duration", quotasWithRunDuration(120));
    workflowService.create(workspace, workflow("retry-quota-duration-workflow"));
    WorkflowSubmitRequest request = request();
    request.setTimeout(100L);
    WorkflowRun source =
        workflowService.submit(workspace, "retry-quota-duration-workflow", request, false);
    assertEquals(100L, source.getTimeout());

    // The operator lowers the workspace's run-duration quota after the original run was admitted.
    WorkspaceRequest patch = new WorkspaceRequest();
    patch.setQuotas(quotasWithRunDuration(20));
    workspaceService.patch(workspace, patch);

    WorkflowRun retried = workflowRunService.retry(workspace, source.getId()).getBody();

    assertEquals(20L, retried.getTimeout());
  }

  @Test
  void anAutoRetryRefusedByQuotaLeavesTheRunTimedOutAndDoesNotThrow() {
    String workspace = createWorkspace("retry-quota-auto", quotasWithConcurrentRuns(0));
    workflowService.create(workspace, workflow("retry-quota-auto-workflow"));
    WorkflowSubmitRequest request = request();
    request.setRetries(1L);
    WorkflowRun source =
        workflowService.submit(workspace, "retry-quota-auto-workflow", request, false);

    // The state the watcher's timeout sweep finds a run in.
    WorkflowRunEntity running = workflowRunRepository.findById(source.getId()).get();
    running.setStatus(RunStatus.running);
    running.setPhase(RunPhase.running);
    workflowRunRepository.save(running);

    setFeatureSetting(QUOTA_FEATURE, true);

    // Must not throw: the run is already terminal by the time the retry is evaluated, so a
    // refused retry is the whole outcome rather than a failure of the timeout path.
    workflowExecutionService.timeoutWorkflow(source.getId(), "Timed out for the test");

    assertEquals(
        RunStatus.timedout, workflowRunRepository.findById(source.getId()).get().getStatus());
    assertEquals(1, runsOf(source.getWorkflowRef()).size(), "the refused retry created no clone");
  }

  private List<WorkflowRunEntity> runsOf(String workflowRef) {
    return workflowRunRepository.findAll().stream()
        .filter(r -> workflowRef.equals(r.getWorkflowRef()))
        .toList();
  }

  private static WorkflowSubmitRequest request() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private String createWorkspace(String name, Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  private static Quotas quotasWithConcurrentRuns(int runs) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(runs);
    return quotas;
  }

  private static Quotas quotasWithRunDuration(int minutes) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunDuration(minutes);
    return quotas;
  }

  private static Workflow workflow(String name) {
    Workflow workflow = new Workflow();
    workflow.setName(name);
    workflow.setTasks(
        new LinkedList<>(
            List.of(
                task("start", TaskType.start, null, null),
                task("work", TaskType.template, TASK_SLUG, "start"),
                task("end", TaskType.end, null, "work"))));
    return workflow;
  }

  private static WorkflowTask task(
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
}
