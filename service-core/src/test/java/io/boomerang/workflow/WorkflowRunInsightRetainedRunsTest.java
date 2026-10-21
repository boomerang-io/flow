package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.ServerLabel;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightSummary.WorkflowRow;
import io.boomerang.common.model.WorkflowRunInsightTasks;
import io.boomerang.common.model.WorkflowRunInsightTasks.Row;
import io.boomerang.common.model.WorkflowRunInsightTasks.SlowTaskRun;
import io.boomerang.common.model.WorkflowRunInsightUsage;
import io.boomerang.common.model.WorkflowRunInsightUsage.ModelUsage;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail.TaskRow;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.time.Instant;
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
    // Two runs allow retries and one run is a retry: only the retry counts as retried.
    fast.setRetries(2L);
    broken.setRetries(2L);
    slow.setTrigger("retry");
    slow.setInitiatedByRef(broken.getId());
    workflowRunRepository.saveAll(List.of(fast, slow, broken));
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
    assertThat(detail.getRetriedRuns()).isEqualTo(1);
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

  @Test
  void taskRunsGroupByTheModelLabelWithCountsDurationsAndUsage() {
    Workflow workflow = createdWorkflow("insight-tasks-model-");
    String workflowRef = refOf(workflow);
    String run = completedRun(workflowRef, RunStatus.succeeded, 1_000).getId();
    Map<String, String> none = Map.of();
    RunStatus succeeded = RunStatus.succeeded;
    modelTask(workflowRef, run, "draft", succeeded, 100, "m1", reported("10", "15", "0.5"));
    modelTask(workflowRef, run, "review", succeeded, 300, "m1", reported("20", "25", "1.5"));
    modelTask(workflowRef, run, "check", RunStatus.failed, 900, "m1", none);
    modelTask(workflowRef, run, "draft2", succeeded, 200, "m2", Map.of("totalTokens", "7"));
    // Skipped, in flight or not an ai task: none of them counts.
    modelTask(workflowRef, run, "skip", RunStatus.skipped, 0, "m1", none);
    TaskRunEntity running = modelTask(workflowRef, run, "live", RunStatus.running, 0, "m1", none);
    running.setPhase(RunPhase.running);
    taskRunRepository.save(running);
    TaskRunEntity work =
        timedTask(workflowRef, run, "work", TaskType.template, RunStatus.succeeded, 50, null);
    work.getLabels().put(ServerLabel.MODEL, "m1");
    taskRunRepository.save(work);

    WorkflowRunInsightTasks insight =
        tasks(
            workflow,
            Optional.empty(),
            Optional.of(List.of("ai")),
            "label:" + ServerLabel.MODEL,
            null,
            null,
            0);

    assertThat(insight.getRows()).extracting(Row::getKey).containsExactly("m1", "m2");
    Row m1 = insight.getRows().get(0);
    assertThat(m1.getTaskRuns()).isEqualTo(3);
    assertThat(m1.getSucceeded()).isEqualTo(2);
    assertThat(m1.getFailed()).isEqualTo(1);
    assertThat(m1.getMeanDuration()).isEqualTo(200);
    assertThat(m1.getP50Duration()).isEqualTo(100);
    assertThat(m1.getP95Duration()).isEqualTo(300);
    assertThat(m1.getMaxDuration()).isEqualTo(300);
    assertThat(m1.getAiTaskRuns()).isEqualTo(3);
    assertThat(m1.getPromptTokens()).isEqualTo(30);
    assertThat(m1.getTotalTokens()).isEqualTo(40);
    assertThat(m1.getCost()).isEqualTo(2.0, within(1e-9));
    Row m2 = insight.getRows().get(1);
    assertThat(m2.getTaskRuns()).isEqualTo(1);
    assertThat(m2.getTotalTokens()).isEqualTo(7);
    assertThat(m2.getCost()).as("no task run of the row reported a cost").isNull();
    assertThat(insight.getSlowest()).isNull();
  }

  @Test
  void taskRunsByTaskCountAForeachTasksItemsUnderItsNameAndNoStructuralNode() {
    Workflow workflow = createdWorkflow("insight-tasks-task-");
    String workflowRef = refOf(workflow);
    String run = completedRun(workflowRef, RunStatus.succeeded, 1_000).getId();
    workTask(workflowRef, run, RunStatus.succeeded, 400, null);
    TaskRunEntity fan =
        timedTask(workflowRef, run, "fan", TaskType.template, RunStatus.succeeded, 9_000, null);
    fan.setForeach(new WorkflowTaskForeach());
    taskRunRepository.save(fan);
    for (int index = 0; index < 2; index++) {
      TaskRunEntity item =
          timedTask(
              workflowRef, run, "fan[" + index + "]", TaskType.template, RunStatus.succeeded, 10, null);
      item.setParentRef(fan.getId());
      item.setIndex(index);
      taskRunRepository.save(item);
    }

    WorkflowRunInsightTasks insight =
        tasks(workflow, Optional.empty(), Optional.empty(), "task", null, null, 0);

    assertThat(insight.getRows()).extracting(Row::getKey).containsExactly("fan", "work");
    assertThat(insight.getRows().get(0).getTaskRuns()).isEqualTo(2);
    assertThat(insight.getRows().get(0).getMaxDuration()).isEqualTo(10);
    assertThat(insight.getRows().get(1).getTaskRuns()).isEqualTo(1);
  }

  @Test
  void taskRunsBucketByTheirStartAndSpanTheRunsSharingALabel() {
    Workflow workflow = createdWorkflow("insight-tasks-span-");
    String workflowRef = refOf(workflow);
    Date dayOne = at("2026-01-05T10:00:00Z");
    Date dayTwo = at("2026-01-06T23:30:00Z");
    // One attempt across two runs: its first task starts at dayOne, its last ends 5 s after dayTwo.
    String parent = attemptRun(workflowRef, "a1");
    String child = attemptRun(workflowRef, "a1");
    startedTask(workflowRef, parent, "agent", dayOne, 1_000);
    startedTask(workflowRef, child, "chair", dayTwo, 5_000);
    // A second attempt of 2 s, and a run with no attempt label, which spans nothing.
    String other = attemptRun(workflowRef, "a2");
    startedTask(workflowRef, other, "agent", dayOne, 2_000);
    String unlabelled = completedRun(workflowRef, RunStatus.succeeded, 1).getId();
    startedTask(workflowRef, unlabelled, "agent", dayOne, 1);

    WorkflowRunInsightTasks byDay =
        tasks(workflow, Optional.empty(), Optional.empty(), "task", "day", null, 0);

    assertThat(byDay.getRows())
        .extracting(Row::getBucket, Row::getKey, Row::getTaskRuns)
        .containsExactly(
            tuple(at("2026-01-05T00:00:00Z"), "agent", 3L),
            tuple(at("2026-01-06T00:00:00Z"), "chair", 1L));

    WorkflowRunInsightTasks spans =
        tasks(workflow, Optional.empty(), Optional.empty(), null, null, "label:attempt", 0);

    assertThat(spans.getRows()).hasSize(1);
    Row all = spans.getRows().get(0);
    assertThat(all.getKey()).isNull();
    assertThat(all.getTaskRuns()).isEqualTo(4);
    assertThat(all.getSpans()).isEqualTo(2);
    long attemptOne = dayTwo.getTime() + 5_000 - dayOne.getTime();
    assertThat(all.getP50Span()).isEqualTo(2_000);
    assertThat(all.getMaxSpan()).isEqualTo(attemptOne);
  }

  @Test
  void theSlowestListTheLongestTaskRunsFirstAndAnItemLabelGroupsAChildsTaskRuns() {
    Workflow workflow = createdWorkflow("insight-tasks-slowest-");
    String workflowRef = refOf(workflow);
    for (long duration : List.of(100L, 700L, 400L)) {
      TaskRunEntity parse =
          timedTask(
              workflowRef,
              completedRun(workflowRef, RunStatus.succeeded, 1_000).getId(),
              "parse",
              TaskType.template,
              RunStatus.succeeded,
              duration,
              null);
      // As a for-each run-workflow child's task runs carry their item.
      parse.getLabels().put(ServerLabel.ITEM, (duration > 200) ? "agent-a" : "agent-b");
      taskRunRepository.save(parse);
    }

    WorkflowRunInsightTasks insight =
        tasks(
            workflow,
            Optional.of(List.of("parse")),
            Optional.empty(),
            "label:" + ServerLabel.ITEM,
            null,
            null,
            2);

    assertThat(insight.getSlowest())
        .extracting(SlowTaskRun::getDuration)
        .containsExactly(700L, 400L);
    assertThat(insight.getSlowest().get(0).getWorkflowRef()).isEqualTo(workflowRef);
    assertThat(insight.getRows())
        .extracting(Row::getKey, Row::getTaskRuns, Row::getMaxDuration)
        .containsExactly(tuple("agent-a", 2L, 700L), tuple("agent-b", 1L, 100L));
  }

  @Test
  void anUnknownGroupingBucketSpanOrSlowestCountIsRefused() {
    Workflow workflow = createdWorkflow("insight-tasks-refused-");

    for (Runnable call :
        List.<Runnable>of(
            () -> tasks(workflow, Optional.empty(), Optional.empty(), "owner", null, null, 0),
            () -> tasks(workflow, Optional.empty(), Optional.empty(), "param:model", null, null, 0),
            () -> tasks(workflow, Optional.empty(), Optional.empty(), null, "week", null, 0),
            () -> tasks(workflow, Optional.empty(), Optional.empty(), null, null, "param:x", 0),
            () -> tasks(workflow, Optional.empty(), Optional.empty(), null, null, null, 101))) {
      assertThatThrownBy(call::run)
          .isInstanceOf(BoomerangException.class)
          .extracting(ex -> ((BoomerangException) ex).getCode())
          .isEqualTo(BoomerangError.QUERY_INVALID_FILTERS.getCode());
    }
  }

  // The workflow's task runs of the last minute, as the route takes them.
  private WorkflowRunInsightTasks tasks(
      Workflow workflow,
      Optional<List<String>> tasks,
      Optional<List<String>> types,
      String groupBy,
      String bucket,
      String spanBy,
      int slowest) {
    return insightService.tasks(
        SYSTEM_WORKSPACE,
        new Date(System.currentTimeMillis() - 60_000),
        new Date(System.currentTimeMillis() + 60_000),
        Optional.of(List.of(workflow.getName())),
        Optional.empty(),
        tasks,
        types,
        Optional.ofNullable(groupBy),
        Optional.ofNullable(bucket),
        Optional.ofNullable(spanBy),
        slowest);
  }

  private TaskRunEntity timedTask(
      String workflowRef,
      String workflowRunRef,
      String name,
      TaskType type,
      RunStatus status,
      long duration,
      Date startTime) {
    TaskRunEntity task =
        savedTaskRun(name, type, status, RunPhase.completed, workflowRef, workflowRunRef);
    task.setDuration(duration);
    task.setStartTime(startTime);
    return taskRunRepository.save(task);
  }

  private void startedTask(
      String workflowRef, String workflowRunRef, String name, Date startTime, long duration) {
    timedTask(
        workflowRef, workflowRunRef, name, TaskType.template, RunStatus.succeeded, duration, startTime);
  }

  private String attemptRun(String workflowRef, String attempt) {
    WorkflowRunEntity run = completedRun(workflowRef, RunStatus.succeeded, 1_000);
    run.getLabels().put("attempt", attempt);
    return workflowRunRepository.save(run).getId();
  }

  private static Date at(String instant) {
    return Date.from(Instant.parse(instant));
  }

  // The usage results an ai task reports: prompt and total tokens, and a cost.
  private static Map<String, String> reported(String prompt, String total, String cost) {
    return Map.of("promptTokens", prompt, "totalTokens", total, "cost", cost);
  }

  // An ai task run as admission leaves it: labelled with its model, reporting these results.
  private TaskRunEntity modelTask(
      String workflowRef,
      String workflowRunRef,
      String name,
      RunStatus status,
      long duration,
      String model,
      Map<String, String> results) {
    TaskRunEntity task =
        timedTask(workflowRef, workflowRunRef, name, TaskType.ai, status, duration, null);
    task.getLabels().put(ServerLabel.MODEL, model);
    task.setResults(results(results));
    return taskRunRepository.save(task);
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
