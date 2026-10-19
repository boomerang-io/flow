package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightSummary.WorkflowRow;
import io.boomerang.common.model.WorkflowRunInsightUsage;
import io.boomerang.common.model.WorkflowRunInsightUsage.ModelUsage;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail.TaskRow;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Insights read the runs and task runs a workspace still holds, scoped through the relationship
 * graph, and roll them up per workflow and per task.
 */
@TestPropertySource(properties = {"flow.workspace.single=true", "flow.security.enabled=false"})
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
        insightService.workflowDetail(
            SYSTEM_WORKSPACE, workflow.getName(), from, to, Optional.empty());

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

  @Test
  void labelsNarrowTheSummaryAndTheDetail() {
    Workflow workflow = createdWorkflow("insight-labels-");
    String workflowRef = refOf(workflow);
    Date from = new Date(System.currentTimeMillis() - 60_000);
    Date to = new Date(System.currentTimeMillis() + 60_000);
    labelledRun(workflowRef, "knowledge");
    labelledRun(workflowRef, "eval");
    // URL-encoded key=value, as the run query takes it; the dot in the key is matched as stored.
    Optional<List<String>> labels = Optional.of(List.of("archie.analysisType%3Dknowledge"));

    WorkflowRunInsightSummary summary =
        insightService.summary(
            SYSTEM_WORKSPACE,
            from,
            to,
            Optional.of(List.of(workflow.getName())),
            labels,
            Optional.empty(),
            Optional.empty());
    WorkflowRunInsightWorkflowDetail detail =
        insightService.workflowDetail(SYSTEM_WORKSPACE, workflow.getName(), from, to, labels);

    assertThat(summary.getTotals().getRuns()).isEqualTo(1);
    assertThat(detail.getRuns()).isEqualTo(1);
  }

  @Test
  void aLabelWithoutAValueIsRefused() {
    Workflow workflow = createdWorkflow("insight-bad-label-");
    Date now = new Date();

    assertThatThrownBy(
            () ->
                insightService.workflowDetail(
                    SYSTEM_WORKSPACE,
                    workflow.getName(),
                    now,
                    now,
                    Optional.of(List.of("archie.analysisType"))))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getCode())
        .isEqualTo(BoomerangError.QUERY_INVALID_FILTERS.getCode());
  }

  @Test
  void usageSumsTheAiTaskRunsOverallByModelAndByTask() {
    Workflow workflow = createdWorkflow("insight-usage-");
    String workflowRef = refOf(workflow);
    Date from = new Date(System.currentTimeMillis() - 60_000);
    Date to = new Date(System.currentTimeMillis() + 60_000);
    String runA = completedRun(workflowRef, RunStatus.succeeded, 1_000).getId();
    String runB = completedRun(workflowRef, RunStatus.succeeded, 1_000).getId();
    aiTask(workflowRef, runA, "draft", usage("m1", "100", "50", "150", "0.002"));
    // An empty cost and a non-numeric value add nothing.
    Map<String, String> unreported = usage("m2", "20", "10", "30", "");
    unreported.put("reasoningTokens", "n/a");
    aiTask(workflowRef, runB, "draft", unreported);
    // A for-each parent holds its items' results as arrays: only the items count, under its name.
    TaskRunEntity fan =
        aiTask(workflowRef, runB, "fan", Map.of("model", "[\"m1\",\"m1\"]", "totalTokens", "[5,7]"));
    fan.setForeach(new WorkflowTaskForeach());
    taskRunRepository.save(fan);
    aiItem(workflowRef, runB, fan, 0, Map.of("model", "m1", "totalTokens", "5"));
    aiItem(workflowRef, runB, fan, 1, Map.of("model", "m1", "totalTokens", "7", "cost", "0.001"));
    // A task that is not an ai task never counts, whatever its results are called.
    TaskRunEntity work =
        savedTaskRun("work", TaskType.template, RunStatus.succeeded, RunPhase.completed, workflowRef, runB);
    work.setResults(results(Map.of("totalTokens", "999", "cost", "9")));
    taskRunRepository.save(work);
    // A run of a workflow this workspace does not hold is never counted.
    String foreignModel = "foreign-" + System.nanoTime();
    String foreignWorkflow = "not-in-this-workspace-" + System.nanoTime();
    aiTask(
        foreignWorkflow,
        completedRun(foreignWorkflow, RunStatus.succeeded, 1_000).getId(),
        "draft",
        usage(foreignModel, "1", "1", "1000", "5"));

    WorkflowRunInsightUsage usage =
        insightService
            .summary(
                SYSTEM_WORKSPACE,
                from,
                to,
                Optional.of(List.of(workflow.getName())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
            .getUsage();

    assertThat(usage.getAiTaskRuns()).isEqualTo(4);
    assertThat(usage.getPromptTokens()).isEqualTo(120);
    assertThat(usage.getCompletionTokens()).isEqualTo(60);
    assertThat(usage.getTotalTokens()).isEqualTo(192);
    assertThat(usage.getReasoningTokens()).isZero();
    assertThat(usage.getCost()).isCloseTo(0.003, within(1e-9));
    assertThat(usage.getByModel()).extracting(ModelUsage::getModel).containsExactly("m1", "m2");
    assertThat(usage.getByModel().get(0).getAiTaskRuns()).isEqualTo(3);
    assertThat(usage.getByModel().get(0).getTotalTokens()).isEqualTo(162);
    assertThat(usage.getByModel().get(1).getTotalTokens()).isEqualTo(30);
    assertThat(usage.getByModel().get(1).getCost()).isNull();

    WorkflowRunInsightWorkflowDetail detail =
        insightService.workflowDetail(
            SYSTEM_WORKSPACE, workflow.getName(), from, to, Optional.empty());

    assertThat(detail.getUsage().getTotalTokens()).isEqualTo(192);
    Map<String, TaskRow> rows = new HashMap<>();
    detail.getTasks().forEach(row -> rows.put(row.getName(), row));
    assertThat(rows.get("draft").getTotalTokens()).isEqualTo(180);
    assertThat(rows.get("draft").getCost()).isCloseTo(0.002, within(1e-9));
    assertThat(rows.get("fan").getTotalTokens()).isEqualTo(12);
    assertThat(rows.get("fan").getCost()).isCloseTo(0.001, within(1e-9));
    assertThat(rows.get("work").getTotalTokens()).isNull();
    assertThat(rows.get("work").getCost()).isNull();

    // Across the whole workspace the foreign run's model still never appears.
    assertThat(
            insightService
                .summary(
                    SYSTEM_WORKSPACE,
                    from,
                    to,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty())
                .getUsage()
                .getByModel())
        .extracting(ModelUsage::getModel)
        .doesNotContain(foreignModel);
  }

  private Workflow createdWorkflow(String prefix) {
    return workflowService.create(
        SYSTEM_WORKSPACE, runnableWorkflow(prefix + System.nanoTime(), TASK_SLUG));
  }

  // The id the runs must carry: resolved through the graph, as the service resolves names.
  private String refOf(Workflow workflow) {
    return relationshipService
        .filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(workflow.getName())),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(SYSTEM_WORKSPACE)),
            false)
        .get(0);
  }

  private void labelledRun(String workflowRef, String analysisType) {
    WorkflowRunEntity run = completedRun(workflowRef, RunStatus.succeeded, 1_000);
    run.getLabels().put("archie.analysisType", analysisType);
    workflowRunRepository.save(run);
  }

  private static Map<String, String> usage(
      String model, String prompt, String completion, String total, String cost) {
    Map<String, String> results = new HashMap<>();
    results.put("model", model);
    results.put("promptTokens", prompt);
    results.put("completionTokens", completion);
    results.put("totalTokens", total);
    results.put("cost", cost);
    return results;
  }

  private TaskRunEntity aiTask(
      String workflowRef, String workflowRunRef, String name, Map<String, String> results) {
    TaskRunEntity task =
        savedTaskRun(name, TaskType.ai, RunStatus.succeeded, RunPhase.completed, workflowRef, workflowRunRef);
    task.setResults(results(results));
    return taskRunRepository.save(task);
  }

  private void aiItem(
      String workflowRef,
      String workflowRunRef,
      TaskRunEntity parent,
      int index,
      Map<String, String> results) {
    TaskRunEntity item =
        savedTaskRun(
            parent.getName() + "[" + index + "]",
            TaskType.ai,
            RunStatus.succeeded,
            RunPhase.completed,
            workflowRef,
            workflowRunRef);
    item.setParentRef(parent.getId());
    item.setIndex(index);
    item.setResults(results(results));
    taskRunRepository.save(item);
  }

  private static List<RunResult> results(Map<String, String> values) {
    List<RunResult> results = new ArrayList<>();
    values.forEach(
        (name, value) -> {
          RunResult result = new RunResult();
          result.setName(name);
          result.setValue(value);
          results.add(result);
        });
    return results;
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
