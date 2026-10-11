package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightSummary.WorkflowRow;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Insights read the runs and task runs a workspace still holds, scoped through the relationship
 * graph, and roll them up per workflow and per task.
 */
@TestPropertySource(properties = "flow.mode=engine")
class WorkflowRunInsightRetainedRunsTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM_WORKSPACE = "system";
  private static final String TASK_SLUG = "insight-retained-runs-task";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunInsightService insightService;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    if (!relationshipService.doesSlugOrRefExistForType(
        RelationshipType.WORKSPACE, SYSTEM_WORKSPACE)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          SYSTEM_WORKSPACE,
          SYSTEM_WORKSPACE,
          Optional.empty(),
          Optional.empty());
    }
  }

  @Test
  void summarisesRetainedRunsPerWorkflowAndDetailsTasksAndFailures() {
    Workflow workflow =
        workflowService.create(
            SYSTEM_WORKSPACE, runnableWorkflow("insight-retained-" + System.nanoTime(), TASK_SLUG));
    // The id the runs must carry: resolved through the graph, as the service resolves names.
    String workflowRef =
        relationshipService
            .filter(
                RelationshipType.WORKFLOW,
                Optional.of(List.of(workflow.getName())),
                Optional.of(RelationshipType.WORKSPACE),
                Optional.of(List.of(SYSTEM_WORKSPACE)),
                false)
            .get(0);
    Date from = new Date(System.currentTimeMillis() - 60_000);
    Date to = new Date(System.currentTimeMillis() + 60_000);

    WorkflowRunEntity fast = completedRun(workflowRef, RunStatus.succeeded, 1_000);
    WorkflowRunEntity slow = completedRun(workflowRef, RunStatus.succeeded, 9_000);
    WorkflowRunEntity broken = completedRun(workflowRef, RunStatus.failed, 500);
    workTask(workflowRef, fast.getId(), RunStatus.succeeded, 800, null);
    workTask(workflowRef, slow.getId(), RunStatus.succeeded, 8_800, null);
    workTask(workflowRef, broken.getId(), RunStatus.failed, 400, "exit code 2");

    WorkflowRunInsightSummary summary =
        insightService.summary(
            SYSTEM_WORKSPACE,
            from,
            to,
            Optional.of(List.of(workflow.getName())),
            Optional.empty(),
            Optional.empty());

    assertThat(summary.getTotals().getRuns()).isEqualTo(3);
    assertThat(summary.getTotals().getSucceeded()).isEqualTo(2);
    assertThat(summary.getTotals().getFailed()).isEqualTo(1);
    assertThat(summary.getTotals().getSuccessRate()).isEqualTo(2.0 / 3);
    assertThat(summary.getTotals().getP50Duration()).isEqualTo(1_000);
    assertThat(summary.getPrevious().getRuns()).isZero();
    assertThat(summary.getDaily()).hasSizeBetween(1, 2);
    assertThat(summary.getWorkflows()).hasSize(1);
    WorkflowRow row = summary.getWorkflows().get(0);
    assertThat(row.getWorkflowRef()).isEqualTo(workflowRef);
    assertThat(row.getWorkflowName()).isEqualTo(workflow.getName());
    assertThat(row.getLastFailureRunRef()).isEqualTo(broken.getId());
    assertThat(row.getRecent()).hasSize(7);

    WorkflowRunInsightWorkflowDetail detail =
        insightService.workflowDetail(SYSTEM_WORKSPACE, workflow.getName(), from, to);

    assertThat(detail.getWorkflowRef()).isEqualTo(workflowRef);
    assertThat(detail.getRuns()).isEqualTo(3);
    // start and end nodes carry no work, so the one row is the work task.
    assertThat(detail.getTasks()).hasSize(1);
    assertThat(detail.getTasks().get(0).getName()).isEqualTo("work");
    assertThat(detail.getTasks().get(0).getRuns()).isEqualTo(3);
    assertThat(detail.getTasks().get(0).getFailed()).isEqualTo(1);
    assertThat(detail.getTasks().get(0).getP50Duration()).isEqualTo(800);
    assertThat(detail.getFailures()).hasSize(1);
    assertThat(detail.getFailures().get(0).getStatus()).isEqualTo("failed");
    assertThat(detail.getFailures().get(0).getTaskName()).isEqualTo("work");
    assertThat(detail.getFailures().get(0).getReason()).isEqualTo("exit code 2");
    assertThat(detail.getFailures().get(0).getCount()).isEqualTo(1);
    assertThat(detail.getFailures().get(0).getLastRunRef()).isEqualTo(broken.getId());
  }

  private WorkflowRunEntity completedRun(String workflowRef, RunStatus status, long duration) {
    WorkflowRunEntity run = savedWorkflowRun(workflowRef, status, RunPhase.completed);
    run.setDuration(duration);
    run.setTrigger("manual");
    return workflowRunRepository.save(run);
  }

  private void workTask(
      String workflowRef, String workflowRunRef, RunStatus status, long duration, String message) {
    savedTaskRun("start", TaskType.start, RunStatus.succeeded, RunPhase.completed, workflowRef, workflowRunRef);
    TaskRunEntity work =
        savedTaskRun("work", TaskType.template, status, RunPhase.completed, workflowRef, workflowRunRef);
    work.setDuration(duration);
    work.setStatusMessage(message);
    taskRunRepository.save(work);
    savedTaskRun("end", TaskType.end, RunStatus.succeeded, RunPhase.completed, workflowRef, workflowRunRef);
  }
}
