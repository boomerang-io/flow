package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.BackoffType;
import io.boomerang.common.enums.ConcurrencyOverflow;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RetryBackoff;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowConcurrency;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskRetry;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.EngineConstants;
import io.boomerang.workflow.model.CanvasNode;
import io.boomerang.workflow.model.CanvasNodePosition;
import io.boomerang.workflow.model.WorkflowCanvas;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A task node's timeout and retry setting and a workflow's concurrency setting survive the canvas
 * round trip the editor saves through, are validated at save, and the node's retry setting lands
 * on its task run when a run's task list is built.
 */
class WorkflowRunSettingsTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "run-settings-test-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
  }

  private static WorkflowTaskRetry retry(int max, BackoffType type, Long initial, Long cap) {
    WorkflowTaskRetry retry = new WorkflowTaskRetry();
    retry.setMax(max);
    RetryBackoff backoff = new RetryBackoff();
    backoff.setType(type);
    backoff.setInitial(initial);
    backoff.setMax(cap);
    retry.setBackoff(backoff);
    return retry;
  }

  private static WorkflowTask work(Workflow workflow) {
    return workflow.getTasks().stream().filter(t -> "work".equals(t.getName())).findFirst().get();
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  @Test
  void theNodeAndWorkflowSettingsSurviveTheCanvasRoundTrip() {
    Workflow workflow = runnableWorkflow("settings-canvas", TASK_SLUG);
    work(workflow).setTimeout(15L);
    work(workflow).setRetry(retry(2, BackoffType.exponential, 30L, 300L));
    WorkflowConcurrency concurrency = new WorkflowConcurrency();
    concurrency.setLimit(1);
    concurrency.setOverflow(ConcurrencyOverflow.skip);
    workflow.setConcurrency(concurrency);

    WorkflowCanvas canvas = workflowService.convertWorkflowToCanvas(workflow);
    // The editor places every node; the conversion back reads the position.
    canvas.getNodes().forEach(n -> n.setPosition(new CanvasNodePosition()));
    CanvasNode node =
        canvas.getNodes().stream().filter(n -> "work".equals(n.getData().getName())).findFirst().get();
    assertEquals(15L, node.getData().getTimeout());
    assertEquals(2, node.getData().getRetry().getMax());
    assertEquals(1, canvas.getConcurrency().getLimit());

    Workflow back = workflowService.convertCanvasToWorkflow(canvas);
    assertEquals(15L, work(back).getTimeout());
    assertEquals(2, work(back).getRetry().getMax());
    assertEquals(BackoffType.exponential, work(back).getRetry().getBackoff().getType());
    assertEquals(300L, work(back).getRetry().getBackoff().getMax());
    assertEquals(ConcurrencyOverflow.skip, back.getConcurrency().getOverflow());
  }

  @Test
  void aLimitBelowOneOrARetryOverTheCeilingIsRefusedAtSave() {
    String workspace = workspace("settings-invalid");
    Workflow zeroLimit = runnableWorkflow("settings-zero-limit", TASK_SLUG);
    WorkflowConcurrency concurrency = new WorkflowConcurrency();
    concurrency.setLimit(0);
    zeroLimit.setConcurrency(concurrency);
    BoomerangException refused =
        assertThrows(BoomerangException.class, () -> workflowService.create(workspace, zeroLimit));
    assertEquals("WORKFLOW_INVALID_REQ", refused.getReason());

    Workflow tooManyRetries = runnableWorkflow("settings-too-many-retries", TASK_SLUG);
    work(tooManyRetries)
        .setRetry(retry(EngineConstants.MAX_FAILURE_RETRIES + 1, BackoffType.fixed, 10L, null));
    refused =
        assertThrows(
            BoomerangException.class, () -> workflowService.create(workspace, tooManyRetries));
    assertEquals("WORKFLOW_INVALID_REQ", refused.getReason());
  }

  @Test
  void theNodesRetrySettingLandsOnItsTaskRun() {
    String workspace = workspace("settings-task-run");
    Workflow workflow = runnableWorkflow("settings-task-run-workflow", TASK_SLUG);
    work(workflow).setRetry(retry(3, BackoffType.fixed, 20L, null));
    workflowService.create(workspace, workflow);
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);

    WorkflowRun run = workflowService.submit(workspace, workflow.getName(), request, false);

    List<TaskRunEntity> tasks = taskRunRepository.findByWorkflowRunRef(run.getId());
    TaskRunEntity work = tasks.stream().filter(t -> "work".equals(t.getName())).findFirst().get();
    assertEquals(3, work.getRetry().getMax());
    assertEquals(BackoffType.fixed, work.getRetry().getBackoff().getType());
    assertEquals(20L, work.getRetry().getBackoff().getInitial());
    assertEquals(0, work.getRetry().getFailures());
    assertNull(work.getRetry().getAfter(), "claimable at once");
    TaskRunEntity start = tasks.stream().filter(t -> "start".equals(t.getName())).findFirst().get();
    assertNull(start.getRetry(), "only the node that asked carries the setting");
  }
}
