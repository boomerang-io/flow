package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.engine.AbstractEngineIntegrationTest;
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
 * A child run, submitted by its parent's runworkflow task, is never held to the monthly quotas: a
 * running parent's child starts after the month is spent, so the parent is never stopped halfway,
 * and it still counts as a run of the month.
 */
class ChildRunMonthlyExemptionTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "child-run-exemption-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private QuotaService quotaService;

  private String runWorkflowTaskSlug;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    Task runWorkflow = new Task();
    runWorkflow.setName("child-run-exemption-runworkflow-" + System.nanoTime());
    runWorkflow.setType(TaskType.runworkflow);
    runWorkflow.getSpec().setParams(List.of(param("workflowRef"), param("wait")));
    runWorkflowTaskSlug = taskService.createGlobal(runWorkflow).getName();
  }

  @AfterEach
  void quotasOff() {
    setFeatureSetting("workspaceQuotas", false);
  }

  @Test
  void aChildSubmittedAfterTheMonthIsSpentRunsAndIsCounted() {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(0);
    quotas.setMaxWorkflowRunMonthly(1);
    quotas.setMaxCumulativeDuration(1);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("child-run-exemption-" + System.nanoTime());
    request.setDisplayName(request.getName());
    request.setQuotas(quotas);
    String workspace = workspaceService.create(request).getName();
    String leaf = "child-run-exemption-leaf";
    workflowService.create(workspace, runnableWorkflow(leaf, TASK_SLUG));
    workflowService.create(workspace, parent("child-run-exemption-parent", leaf));
    setFeatureSetting("workspaceQuotas", true);

    // The parent spends the month's one run; its child is submitted after that.
    String parentRunId =
        workflowService.submit(workspace, "child-run-exemption-parent", manual(), true).getId();

    awaitEngine("the parent's runworkflow task started its child")
        .untilAsserted(
            () -> {
              TaskRunEntity task = runWorkflowTask(parentRunId);
              assertThat(task).isNotNull();
              assertThat(task.getStatus()).isEqualTo(RunStatus.succeeded);
              assertThat(task.getPhase()).isEqualTo(RunPhase.completed);
            });
    String childRunId = resultValue(runWorkflowTask(parentRunId), "workflowRunRef");
    WorkflowRunEntity child = workflowRunRepository.findById(childRunId).orElseThrow();
    assertThat(child.getTrigger()).isEqualTo("task");
    assertThat(child.getStatusReason()).isNull();
    assertThat(quotaService.current(workspace).getCurrentRuns())
        .as("the child counts: two runs against a month of one")
        .isEqualTo(2);
    assertThatThrownBy(() -> workflowService.submit(workspace, leaf, manual(), false))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            spent -> assertThat(spent.getArgs()[0]).isEqualTo(QuotaService.MONTHLY_RUNS));

    workflowRunService.cancel(childRunId);
    workflowRunService.cancel(parentRunId);
  }

  private Workflow parent(String name, String childName) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    WorkflowTask child = new WorkflowTask();
    child.setName("child");
    child.setType(TaskType.runworkflow);
    child.setTaskRef(runWorkflowTaskSlug);
    child.setParams(
        new LinkedList<>(
            List.of(new RunParam("workflowRef", childName), new RunParam("wait", "false"))));
    WorkflowTaskDependency afterStart = new WorkflowTaskDependency();
    afterStart.setTaskRef("start");
    child.setDependencies(new LinkedList<>(List.of(afterStart)));
    // start -> child -> end: the work node is replaced by the runworkflow node.
    workflow.getTasks().set(1, child);
    workflow.getTasks().get(2).getDependencies().get(0).setTaskRef("child");
    return workflow;
  }

  private TaskRunEntity runWorkflowTask(String workflowRunId) {
    return taskRunRepository.findByWorkflowRunRef(workflowRunId).stream()
        .filter(t -> TaskType.runworkflow.equals(t.getType()))
        .findFirst()
        .orElse(null);
  }

  private static String resultValue(TaskRunEntity taskRun, String name) {
    return taskRun.getResults().stream()
        .filter(r -> name.equals(r.getName()))
        .map(RunResult::getValue)
        .map(String::valueOf)
        .findFirst()
        .orElse(null);
  }

  private static WorkflowSubmitRequest manual() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private static AbstractParam param(String name) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("text");
    return param;
  }
}
