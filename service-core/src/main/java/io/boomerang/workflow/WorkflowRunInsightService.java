package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightSummary.Day;
import io.boomerang.common.model.WorkflowRunInsightSummary.Totals;
import io.boomerang.common.model.WorkflowRunInsightSummary.WorkflowRow;
import io.boomerang.common.model.WorkflowRunInsightTasks;
import io.boomerang.common.model.WorkflowRunInsightTasks.Row;
import io.boomerang.common.model.WorkflowRunInsightTasks.SlowTaskRun;
import io.boomerang.common.model.WorkflowRunInsightUsage;
import io.boomerang.common.model.WorkflowRunInsightUsage.ModelUsage;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail.Failure;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail.TaskRow;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Run statistics for the Insights page, computed from the WorkflowRuns and TaskRuns a workspace
 * still holds. A deleted Workflow's runs go with it and are not counted here; the monthly quotas,
 * which must count them, read the audit trail ({@code AuditQueryService}). Every read is a projection of the fields the statistics need, scoped to the workspace's
 * Workflows through the relationship graph like every other run query.
 */
@Service
public class WorkflowRunInsightService {

  private static final Set<RunStatus> UNSUCCESSFUL =
      EnumSet.of(RunStatus.failed, RunStatus.timedout, RunStatus.invalid);
  private static final Set<RunStatus> NOT_SUCCEEDED =
      EnumSet.of(RunStatus.failed, RunStatus.timedout, RunStatus.invalid, RunStatus.cancelled);
  private static final Set<TaskType> STRUCTURAL = EnumSet.of(TaskType.start, TaskType.end);
  private static final int RECENT_DAYS = 7;
  private static final int FAILURE_GROUPS = 10;
  private static final int TASK_ROWS = 1000;
  private static final int SLOWEST_TASK_RUNS = 100;
  // The results an ai task reports that sum; `model` groups them.
  private static final List<String> USAGE_RESULTS =
      List.of(
          "promptTokens",
          "completionTokens",
          "totalTokens",
          "reasoningTokens",
          "cachedPromptTokens",
          "cost");

  private final RelationshipService relationshipService;
  private final WorkflowRepository workflowRepository;
  private final MongoTemplate mongoTemplate;
  private final TaskService taskService;

  public WorkflowRunInsightService(
      RelationshipService relationshipService,
      WorkflowRepository workflowRepository,
      MongoTemplate mongoTemplate,
      TaskService taskService) {
    this.relationshipService = relationshipService;
    this.workflowRepository = workflowRepository;
    this.mongoTemplate = mongoTemplate;
    this.taskService = taskService;
  }

  /**
   * Summarise the workspace's runs created in {@code [from, to)}, with the same-length period
   * before it for comparison. {@code workflows} are names or ids; {@code labels}, {@code statuses}
   * and {@code triggers} narrow the runs counted in both periods.
   */
  public WorkflowRunInsightSummary summary(
      String workspace,
      Date from,
      Date to,
      Optional<List<String>> workflows,
      Optional<List<String>> labels,
      Optional<List<String>> statuses,
      Optional<List<String>> triggers) {
    List<String> workflowRefs = workflowRefs(workspace, workflows);
    Date previousFrom = new Date(from.getTime() - Math.max(to.getTime() - from.getTime(), 0));
    List<WorkflowRunEntity> current = new ArrayList<>();
    List<WorkflowRunEntity> previous = new ArrayList<>();
    for (WorkflowRunEntity run : runs(workflowRefs, previousFrom, to, labels, statuses, triggers)) {
      (run.getCreationDate().before(from) ? previous : current).add(run);
    }
    WorkflowRunInsightSummary summary = new WorkflowRunInsightSummary();
    summary.setFrom(from);
    summary.setTo(to);
    summary.setPreviousFrom(previousFrom);
    summary.setPreviousTo(from);
    summary.setTotals(totals(current));
    summary.setPrevious(totals(previous));
    summary.setDaily(daily(current, from, to));
    summary.setWorkflows(workflowRows(current, to));
    summary.setUsage(aiUsage(current.stream().map(WorkflowRunEntity::getId).toList()).usage());
    return summary;
  }

  /**
   * Detail one Workflow (by name or id) over {@code [from, to)}: task timings, failures and model
   * usage. {@code labels} narrows the runs counted.
   */
  public WorkflowRunInsightWorkflowDetail workflowDetail(
      String workspace, String workflow, Date from, Date to, Optional<List<String>> labels) {
    List<String> workflowRefs = workflowRefs(workspace, Optional.of(List.of(workflow)));
    if (workflowRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    String workflowRef = workflowRefs.get(0);
    List<WorkflowRunEntity> runs =
        runs(List.of(workflowRef), from, to, labels, Optional.empty(), Optional.empty());
    List<String> runRefs = runs.stream().map(WorkflowRunEntity::getId).toList();
    List<TaskRunEntity> taskRuns = taskRuns(runRefs);
    AiUsage usage = aiUsage(runRefs);

    WorkflowRunInsightWorkflowDetail detail = new WorkflowRunInsightWorkflowDetail();
    detail.setWorkflowRef(workflowRef);
    detail.setWorkflowName(workflowNames(List.of(workflowRef)).get(workflowRef));
    detail.setFrom(from);
    detail.setTo(to);
    detail.setRuns(runs.size());
    List<Long> waits = queueWaits(runs);
    detail.setP50QueueWait(percentile(waits, 0.5));
    detail.setP95QueueWait(percentile(waits, 0.95));
    detail.setRetriedRuns(
        runs.stream().filter(run -> run.getRetries() != null && run.getRetries() > 0).count());
    List<TaskRow> rows = slugTaskRefs(taskRows(taskRuns));
    rows.forEach(
        row -> {
          Sums sums = usage.byTask().get(row.getName());
          if (sums != null) {
            row.setTotalTokens(sums.totalTokens());
            row.setCost(sums.cost());
          }
        });
    detail.setTasks(rows);
    detail.setFailures(failures(runs, taskRuns));
    detail.setUsage(usage.usage());
    return detail;
  }

  /**
   * Group the task runs of the workspace's runs created in {@code [from, to)} by one key and,
   * optionally, by time. {@code workflows} and {@code labels} narrow the runs, {@code tasks} (node
   * names) and {@code types} the task runs. {@code groupBy} is task, type, workflow, param:<name>
   * or label:<key>; {@code bucket} is hour, day or month, by each task run's start; {@code spanBy}
   * (label:<key>) measures, per row, the first start to the last end of the task runs of the runs
   * sharing each value of that label. {@code slowest} lists that many of the slowest task runs,
   * each with the values of {@code params}.
   */
  public WorkflowRunInsightTasks tasks(
      String workspace,
      Date from,
      Date to,
      Optional<List<String>> workflows,
      Optional<List<String>> labels,
      Optional<List<String>> tasks,
      Optional<List<String>> types,
      Optional<String> groupBy,
      Optional<String> bucket,
      Optional<String> spanBy,
      int slowest,
      Optional<List<String>> params) {
    Grouping grouping = groupBy.map(Grouping::of).orElse(Grouping.NONE);
    ChronoUnit unit = bucket.map(WorkflowRunInsightService::bucketUnit).orElse(null);
    String spanLabel = spanBy.map(value -> labelKey("spanBy", value)).orElse(null);
    if (slowest < 0 || slowest > SLOWEST_TASK_RUNS) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "slowest");
    }
    WorkflowRunInsightTasks insight = new WorkflowRunInsightTasks();
    insight.setFrom(from);
    insight.setTo(to);
    insight.setGroupBy(groupBy.orElse(null));
    insight.setBucket(bucket.orElse(null));
    insight.setSpanBy(spanBy.orElse(null));
    Map<String, WorkflowRunEntity> runs =
        runsById(workflowRefs(workspace, workflows), from, to, labels);
    Map<String, String> workflowNames =
        "workflow".equals(grouping.kind())
            ? workflowNames(
                runs.values().stream().map(WorkflowRunEntity::getWorkflowRef).distinct().toList())
            : Map.of();
    List<String> shown = (slowest > 0) ? params.orElse(List.of()) : List.of();
    List<String> paramNames = new ArrayList<>(shown);
    if ("param".equals(grouping.kind())) {
      paramNames.add(grouping.name());
    }

    Map<TallyKey, RowTally> tallies = new LinkedHashMap<>();
    PriorityQueue<Document> slowestFirst =
        new PriorityQueue<>(Comparator.comparingLong(WorkflowRunInsightService::durationOf));
    try (Stream<Document> samples = taskRunSamples(runs.keySet(), tasks, types, paramNames)) {
      samples.forEach(
          sample -> {
            WorkflowRunEntity run = runs.get(sample.getString("workflowRunRef"));
            Map<String, Object> values = paramValues(sample);
            Date start = sample.getDate("startTime");
            Date when = (start != null) ? start : sample.getDate("creationDate");
            TallyKey key =
                new TallyKey(
                    grouping.keyOf(sample, values, run, workflowNames),
                    (unit != null && when != null) ? bucketStart(when, unit) : null);
            tallies
                .computeIfAbsent(key, created -> new RowTally(spanLabel != null))
                .add(sample, (spanLabel != null) ? run.getLabels().get(spanLabel) : null);
            if (slowest > 0) {
              slowestFirst.offer(sample);
              if (slowestFirst.size() > slowest) {
                slowestFirst.poll();
              }
            }
          });
    }

    List<Row> rows = new ArrayList<>();
    tallies.forEach((key, tally) -> rows.add(tally.row(key)));
    rows.sort(
        Comparator.comparing(Row::getBucket, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Row::getTaskRuns, Comparator.reverseOrder())
            .thenComparing(Row::getKey, Comparator.nullsLast(Comparator.naturalOrder())));
    insight.setTruncated(rows.size() > TASK_ROWS);
    insight.setRows(new LinkedList<>(rows.subList(0, Math.min(rows.size(), TASK_ROWS))));
    if (slowest > 0) {
      insight.setSlowest(
          slowestFirst.stream()
              .sorted(Comparator.comparingLong(WorkflowRunInsightService::durationOf).reversed())
              .map(sample -> slowTaskRun(sample, runs, shown))
              .collect(toList()));
    }
    return insight;
  }

  // A row's task reference is read as the task's slug form, in one batch; a deleted task keeps its id.
  private List<TaskRow> slugTaskRefs(List<TaskRow> rows) {
    Map<String, String> slugs =
        taskService.slugsByRef(
            rows.stream().map(TaskRow::getTaskRef).filter(Objects::nonNull).toList());
    rows.forEach(row -> row.setTaskRef(slugs.getOrDefault(row.getTaskRef(), row.getTaskRef())));
    return rows;
  }

  // ── Reads ────────────────────────────────────────────────────────────────

  private List<String> workflowRefs(String workspace, Optional<List<String>> workflows) {
    List<String> refs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, workspace, workflows);
    // A workspace the caller cannot reach is a 404; a reachable one with nothing to count is empty.
    if (refs.isEmpty()
        && !relationshipService.check(
            RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.WORKSPACE_INVALID_REF);
    }
    return refs;
  }

  private List<WorkflowRunEntity> runs(
      List<String> workflowRefs,
      Date from,
      Date to,
      Optional<List<String>> labels,
      Optional<List<String>> statuses,
      Optional<List<String>> triggers) {
    if (workflowRefs.isEmpty()) {
      return List.of();
    }
    Query query = new Query(runCriteria(workflowRefs, from, to, labels, statuses, triggers));
    query
        .fields()
        .include(
            "creationDate",
            "startTime",
            "duration",
            "status",
            "phase",
            "trigger",
            "retries",
            "timeout",
            "statusMessage",
            "workflowRef");
    query.with(Sort.by(Sort.Direction.ASC, "creationDate"));
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  // The workspace's runs created in [from, to), narrowed like the run query.
  private static Criteria runCriteria(
      List<String> workflowRefs,
      Date from,
      Date to,
      Optional<List<String>> labels,
      Optional<List<String>> statuses,
      Optional<List<String>> triggers) {
    return new QueryCriteria()
        .in("workflowRef", workflowRefs)
        .between("creationDate", Optional.of(from), Optional.of(to))
        .labels(labels)
        .in("status", statuses)
        .in("trigger", triggers)
        .build();
  }

  /** The top-level task runs of the given runs (a foreach's items stay behind their parent). */
  private List<TaskRunEntity> taskRuns(List<String> workflowRunRefs) {
    if (workflowRunRefs.isEmpty()) {
      return List.of();
    }
    Query query =
        new Query(Criteria.where("workflowRunRef").in(workflowRunRefs).and("parentRef").isNull());
    query
        .fields()
        .include(
            "name",
            "taskRef",
            "type",
            "status",
            "phase",
            "duration",
            "statusMessage",
            "statusReason",
            "workflowRunRef",
            "creationDate");
    query.with(Sort.by(Sort.Direction.ASC, "creationDate"));
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // The runs created in [from, to), by id, holding only the workflow and the labels.
  private Map<String, WorkflowRunEntity> runsById(
      List<String> workflowRefs, Date from, Date to, Optional<List<String>> labels) {
    Map<String, WorkflowRunEntity> runs = new HashMap<>();
    if (workflowRefs.isEmpty()) {
      return runs;
    }
    Query query =
        new Query(
            runCriteria(workflowRefs, from, to, labels, Optional.empty(), Optional.empty()));
    query.fields().include("workflowRef", "labels");
    mongoTemplate.find(query, WorkflowRunEntity.class).forEach(run -> runs.put(run.getId(), run));
    return runs;
  }

  /**
   * Stream one small document per counted task run of the given runs, on the {@code run_tasks}
   * index ({@code workflowRunRef} first): a completed task run that was not skipped, a for-each
   * task's items rather than the task itself, with its node name and only the named params, a
   * secret one never.
   */
  private Stream<Document> taskRunSamples(
      Collection<String> workflowRunRefs,
      Optional<List<String>> tasks,
      Optional<List<String>> types,
      List<String> paramNames) {
    if (workflowRunRefs.isEmpty()) {
      return Stream.empty();
    }
    Criteria type =
        Criteria.where("workflowRunRef")
            .in(workflowRunRefs)
            .and("phase")
            .is(RunPhase.completed)
            .and("status")
            .ne(RunStatus.skipped)
            .and("foreach")
            .is(null)
            .and("type");
    Criteria counted = types.isPresent() ? type.in(types.get()) : type.nin(STRUCTURAL);
    Document fields =
        new Document("taskName", taskName())
            .append("name", 1)
            .append("type", 1)
            .append("status", 1)
            .append("startTime", 1)
            .append("creationDate", 1)
            .append("duration", 1)
            .append("workflowRunRef", 1);
    if (!paramNames.isEmpty()) {
      Document named =
          new Document(
              "$and",
              List.of(
                  new Document("$in", List.of("$$this.name", paramNames)),
                  new Document("$ne", List.of("$$this.type", ParamType.secret.name()))));
      fields.append(
          "params",
          new Document(
              "$filter",
              new Document("input", new Document("$ifNull", List.of("$params", List.of())))
                  .append("cond", named)));
    }
    List<AggregationOperation> stages = new ArrayList<>();
    stages.add(match(counted));
    stages.add(context -> new Document("$project", fields));
    tasks.ifPresent(names -> stages.add(match(Criteria.where("taskName").in(names))));
    return mongoTemplate.aggregateStream(
        newAggregation(stages), TaskRunEntity.class, Document.class);
  }

  /** One group of summed usage: the six results as numbers, and how many task runs reported cost. */
  private record Sums(
      long aiTaskRuns,
      long promptTokens,
      long completionTokens,
      long totalTokens,
      long reasoningTokens,
      long cachedPromptTokens,
      Double cost) {

    static Sums of(Document group) {
      long reportedCost = number(group, "costReported");
      return new Sums(
          number(group, "aiTaskRuns"),
          number(group, "promptTokens"),
          number(group, "completionTokens"),
          number(group, "totalTokens"),
          number(group, "reasoningTokens"),
          number(group, "cachedPromptTokens"),
          (reportedCost > 0) ? ((Number) group.get("cost")).doubleValue() : null);
    }

    private static long number(Document group, String field) {
      Object value = group.get(field);
      return (value instanceof Number number) ? Math.round(number.doubleValue()) : 0;
    }
  }

  /** The usage of a set of runs, overall and per top-level task name. */
  private record AiUsage(WorkflowRunInsightUsage usage, Map<String, Sums> byTask) {}

  /**
   * Sum the usage results of the {@code ai} task runs of the given runs in one aggregation, on the
   * {@code run_tasks} index ({@code workflowRunRef} first). Each task run's results are read as
   * numbers, an empty or non-numeric value becoming null and adding nothing, then grouped overall,
   * by the {@code model} result and by task name. A for-each parent carries its items' results as
   * arrays, so it is left out and its items counted under its name.
   */
  private AiUsage aiUsage(List<String> workflowRunRefs) {
    WorkflowRunInsightUsage usage = new WorkflowRunInsightUsage();
    if (workflowRunRefs.isEmpty()) {
      return new AiUsage(usage, Map.of());
    }
    Document perTaskRun = new Document("model", result("model"));
    for (String name : USAGE_RESULTS) {
      perTaskRun.append(
          name,
          new Document(
              "$convert",
              new Document("input", result(name))
                  .append("to", "double")
                  .append("onError", null)
                  .append("onNull", null)));
    }
    perTaskRun.append("taskName", taskName());
    Document facets =
        new Document("total", List.of(new Document("$group", sums(null))))
            .append("byModel", List.of(new Document("$group", sums("$model"))))
            .append("byTask", List.of(new Document("$group", sums("$taskName"))));
    Document result =
        mongoTemplate
            .aggregate(
                newAggregation(
                    match(
                        Criteria.where("workflowRunRef")
                            .in(workflowRunRefs)
                            .and("type")
                            .is(TaskType.ai)
                            .and("foreach")
                            .is(null)),
                    context -> new Document("$project", perTaskRun),
                    context -> new Document("$facet", facets)),
                TaskRunEntity.class,
                Document.class)
            .getUniqueMappedResult();
    if (result == null) {
      return new AiUsage(usage, Map.of());
    }
    for (Document group : result.getList("total", Document.class)) {
      Sums sums = Sums.of(group);
      usage.setAiTaskRuns(sums.aiTaskRuns());
      usage.setPromptTokens(sums.promptTokens());
      usage.setCompletionTokens(sums.completionTokens());
      usage.setTotalTokens(sums.totalTokens());
      usage.setReasoningTokens(sums.reasoningTokens());
      usage.setCachedPromptTokens(sums.cachedPromptTokens());
      usage.setCost(sums.cost());
    }
    List<ModelUsage> byModel = new ArrayList<>();
    for (Document group : result.getList("byModel", Document.class)) {
      Sums sums = Sums.of(group);
      ModelUsage model = new ModelUsage();
      model.setModel((group.get("_id") != null) ? group.get("_id").toString() : null);
      model.setAiTaskRuns(sums.aiTaskRuns());
      model.setPromptTokens(sums.promptTokens());
      model.setCompletionTokens(sums.completionTokens());
      model.setTotalTokens(sums.totalTokens());
      model.setReasoningTokens(sums.reasoningTokens());
      model.setCachedPromptTokens(sums.cachedPromptTokens());
      model.setCost(sums.cost());
      byModel.add(model);
    }
    byModel.sort(Comparator.comparing(ModelUsage::getTotalTokens, Comparator.reverseOrder()));
    usage.setByModel(byModel);
    Map<String, Sums> byTask = new HashMap<>();
    for (Document group : result.getList("byTask", Document.class)) {
      byTask.put(String.valueOf(group.get("_id")), Sums.of(group));
    }
    return new AiUsage(usage, byTask);
  }

  // The task run's node name: an item is named `<parent>[<index>]`, so the suffix is stripped and
  // the item lands on its parent's row.
  private static Document taskName() {
    Document suffixLength =
        new Document(
            "$add", List.of(2, new Document("$strLenCP", new Document("$toString", "$index"))));
    Document parentName =
        new Document(
            "$substrCP",
            List.of(
                "$name",
                0,
                new Document(
                    "$subtract", List.of(new Document("$strLenCP", "$name"), suffixLength))));
    return new Document("$cond", List.of(isType("$parentRef", "string"), parentName, "$name"));
  }

  // The value of the task run's result with this name, or null when it reported none.
  private static Document result(String name) {
    return new Document(
        "$let",
        new Document(
                "vars",
                new Document(
                    "match",
                    new Document(
                        "$arrayElemAt",
                        List.of(
                            new Document(
                                "$filter",
                                new Document(
                                        "input",
                                        new Document("$ifNull", List.of("$results", List.of())))
                                    .append(
                                        "cond",
                                        new Document("$eq", List.of("$$this.name", name)))),
                            0))))
            .append("in", "$$match.value"));
  }

  // One $group body: a task-run count, each usage result summed, and how many reported cost.
  private static Document sums(String key) {
    Document group = new Document("_id", key).append("aiTaskRuns", new Document("$sum", 1));
    USAGE_RESULTS.forEach(name -> group.append(name, new Document("$sum", "$" + name)));
    return group.append(
        "costReported",
        new Document("$sum", new Document("$cond", List.of(isType("$cost", "double"), 1, 0))));
  }

  // A field is present with this BSON type; unlike `$ne: [field, null]` it is false for a missing field.
  private static Document isType(String field, String type) {
    return new Document("$eq", List.of(new Document("$type", field), type));
  }

  private Map<String, String> workflowNames(List<String> workflowRefs) {
    Map<String, String> names = new HashMap<>();
    workflowRepository
        .findAllById(workflowRefs)
        .forEach(workflow -> names.put(workflow.getId(), workflow.getName()));
    return names;
  }

  // ── Statistics ────────────────────────────────────────────────────────────

  static Totals totals(List<WorkflowRunEntity> runs) {
    Totals totals = new Totals();
    long unsuccessful = 0;
    long max = 0;
    List<Long> succeededDurations = new ArrayList<>();
    for (WorkflowRunEntity run : runs) {
      totals.setRuns(totals.getRuns() + 1);
      totals.getByTrigger().merge((run.getTrigger() != null) ? run.getTrigger() : "unknown", 1L, Long::sum);
      if (run.getPhase() != RunPhase.completed) {
        totals.setInFlight(totals.getInFlight() + 1);
        continue;
      }
      max = Math.max(max, run.getDuration());
      switch (run.getStatus()) {
        case succeeded -> {
          totals.setSucceeded(totals.getSucceeded() + 1);
          succeededDurations.add(run.getDuration());
        }
        case failed -> totals.setFailed(totals.getFailed() + 1);
        case timedout -> totals.setTimedOut(totals.getTimedOut() + 1);
        case cancelled -> totals.setCancelled(totals.getCancelled() + 1);
        default -> totals.setOther(totals.getOther() + 1);
      }
      if (UNSUCCESSFUL.contains(run.getStatus())) {
        unsuccessful++;
      }
    }
    List<Long> waits = queueWaits(runs);
    totals.setSuccessRate(successRate(totals.getSucceeded(), unsuccessful));
    totals.setP50Duration(percentile(succeededDurations, 0.5));
    totals.setP95Duration(percentile(succeededDurations, 0.95));
    totals.setMaxDuration(max);
    totals.setP50QueueWait(percentile(waits, 0.5));
    totals.setP95QueueWait(percentile(waits, 0.95));
    return totals;
  }

  /** succeeded over every run that finished with an outcome; cancelled runs express no outcome. */
  static Double successRate(long succeeded, long unsuccessful) {
    long finished = succeeded + unsuccessful;
    return (finished == 0) ? null : (double) succeeded / finished;
  }

  /** Nearest-rank percentile; 0 over no values. */
  static long percentile(List<Long> values, double fraction) {
    if (values.isEmpty()) {
      return 0;
    }
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.naturalOrder());
    int rank = (int) Math.ceil(fraction * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  static List<Long> queueWaits(List<WorkflowRunEntity> runs) {
    List<Long> waits = new ArrayList<>();
    for (WorkflowRunEntity run : runs) {
      if (run.getStartTime() != null && run.getCreationDate() != null) {
        waits.add(Math.max(run.getStartTime().getTime() - run.getCreationDate().getTime(), 0));
      }
    }
    return waits;
  }

  /** One entry per UTC calendar day of {@code [from, to)}, counting completed runs by outcome. */
  static List<Day> daily(List<WorkflowRunEntity> runs, Date from, Date to) {
    LocalDate first = day(from);
    LocalDate last = (to.after(from)) ? day(new Date(to.getTime() - 1)) : first;
    Map<LocalDate, Day> days = new LinkedHashMap<>();
    for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
      Day day = new Day();
      day.setDate(date.toString());
      days.put(date, day);
    }
    for (WorkflowRunEntity run : runs) {
      Day day = days.get(day(run.getCreationDate()));
      if (day == null || run.getPhase() != RunPhase.completed) {
        continue;
      }
      switch (run.getStatus()) {
        case succeeded -> day.setSucceeded(day.getSucceeded() + 1);
        case failed -> day.setFailed(day.getFailed() + 1);
        case timedout -> day.setTimedOut(day.getTimedOut() + 1);
        case cancelled -> day.setCancelled(day.getCancelled() + 1);
        default -> day.setOther(day.getOther() + 1);
      }
    }
    return new ArrayList<>(days.values());
  }

  static LocalDate day(Date date) {
    return date.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
  }

  private List<WorkflowRow> workflowRows(List<WorkflowRunEntity> runs, Date to) {
    Map<String, List<WorkflowRunEntity>> byWorkflow =
        runs.stream().collect(groupingBy(WorkflowRunEntity::getWorkflowRef, LinkedHashMap::new, toList()));
    Map<String, String> names = workflowNames(new ArrayList<>(byWorkflow.keySet()));
    Date recentFrom =
        Date.from(day(to).minusDays(RECENT_DAYS - 1).atStartOfDay().toInstant(ZoneOffset.UTC));
    List<WorkflowRow> rows = new ArrayList<>();
    for (Map.Entry<String, List<WorkflowRunEntity>> entry : byWorkflow.entrySet()) {
      List<WorkflowRunEntity> workflowRuns = entry.getValue();
      Totals totals = totals(workflowRuns);
      List<Long> succeededDurations =
          workflowRuns.stream()
              .filter(run -> run.getStatus() == RunStatus.succeeded)
              .map(WorkflowRunEntity::getDuration)
              .toList();
      WorkflowRow row = new WorkflowRow();
      row.setWorkflowRef(entry.getKey());
      row.setWorkflowName(names.getOrDefault(entry.getKey(), entry.getKey()));
      row.setRuns(totals.getRuns());
      row.setSucceeded(totals.getSucceeded());
      row.setFailed(totals.getFailed());
      row.setTimedOut(totals.getTimedOut());
      row.setCancelled(totals.getCancelled());
      row.setOther(totals.getOther());
      row.setSuccessRate(totals.getSuccessRate());
      row.setP5Duration(percentile(succeededDurations, 0.05));
      row.setP50Duration(totals.getP50Duration());
      row.setP95Duration(totals.getP95Duration());
      row.setMaxDuration(totals.getMaxDuration());
      // Runs are creation-ascending, so the last one is the newest.
      row.setTimeoutMinutes(workflowRuns.get(workflowRuns.size() - 1).getTimeout());
      for (int i = workflowRuns.size() - 1; i >= 0; i--) {
        WorkflowRunEntity run = workflowRuns.get(i);
        if (run.getPhase() == RunPhase.completed && UNSUCCESSFUL.contains(run.getStatus())) {
          row.setLastFailureDate(run.getCreationDate());
          row.setLastFailureRunRef(run.getId());
          break;
        }
      }
      row.setRecent(daily(workflowRuns, recentFrom, to));
      rows.add(row);
    }
    rows.sort(
        Comparator.comparing(
                WorkflowRow::getSuccessRate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(WorkflowRow::getRuns, Comparator.reverseOrder()));
    return rows;
  }

  /** One row per task name in first-seen order; start and end nodes carry no work. */
  private static List<TaskRow> taskRows(List<TaskRunEntity> taskRuns) {
    Map<String, TaskRow> rows = new LinkedHashMap<>();
    Map<String, List<Long>> succeededDurations = new HashMap<>();
    for (TaskRunEntity taskRun : taskRuns) {
      if (taskRun.getType() != null && STRUCTURAL.contains(taskRun.getType())) {
        continue;
      }
      TaskRow row =
          rows.computeIfAbsent(
              taskRun.getName(),
              name -> {
                TaskRow created = new TaskRow();
                created.setName(name);
                created.setTaskRef(taskRun.getTaskRef());
                return created;
              });
      row.setRuns(row.getRuns() + 1);
      if (taskRun.getStatus() == RunStatus.succeeded) {
        succeededDurations
            .computeIfAbsent(taskRun.getName(), name -> new ArrayList<>())
            .add(taskRun.getDuration());
      } else if (UNSUCCESSFUL.contains(taskRun.getStatus())) {
        row.setFailed(row.getFailed() + 1);
      }
    }
    for (TaskRow row : rows.values()) {
      List<Long> durations = succeededDurations.getOrDefault(row.getName(), List.of());
      row.setP50Duration(percentile(durations, 0.5));
      row.setP95Duration(percentile(durations, 0.95));
    }
    return new ArrayList<>(rows.values());
  }

  /** Runs that did not succeed, grouped by (status, first failing task, reason). */
  private static List<Failure> failures(
      List<WorkflowRunEntity> runs, List<TaskRunEntity> taskRuns) {
    Map<String, List<TaskRunEntity>> tasksByRun =
        taskRuns.stream()
            .collect(groupingBy(TaskRunEntity::getWorkflowRunRef, LinkedHashMap::new, toList()));
    Map<String, Failure> groups = new LinkedHashMap<>();
    for (WorkflowRunEntity run : runs) {
      if (run.getPhase() != RunPhase.completed || !NOT_SUCCEEDED.contains(run.getStatus())) {
        continue;
      }
      TaskRunEntity culprit =
          tasksByRun.getOrDefault(run.getId(), List.of()).stream()
              .filter(taskRun -> NOT_SUCCEEDED.contains(taskRun.getStatus()))
              .findFirst()
              .orElse(null);
      String taskName = (culprit != null) ? culprit.getName() : null;
      String reason =
          firstText(
              (culprit != null) ? culprit.getStatusMessage() : null,
              (culprit != null) ? culprit.getStatusReason() : null,
              run.getStatusMessage());
      Failure failure =
          groups.computeIfAbsent(
              run.getStatus().getStatus() + "|" + taskName + "|" + reason,
              key -> {
                Failure created = new Failure();
                created.setStatus(run.getStatus().getStatus());
                created.setTaskName(taskName);
                created.setReason(reason);
                return created;
              });
      failure.setCount(failure.getCount() + 1);
      // Runs are creation-ascending, so the latest seen is the newest.
      failure.setLastRunRef(run.getId());
      failure.setLastDate(run.getCreationDate());
    }
    return groups.values().stream()
        .sorted(Comparator.comparing(Failure::getCount, Comparator.reverseOrder()))
        .limit(FAILURE_GROUPS)
        .toList();
  }

  // ── Task-run grouping ───────────────────────────────────────────────────

  /** What a task run is grouped by: task, type, workflow, param or label, with its name. */
  private record Grouping(String kind, String name) {
    static final Grouping NONE = new Grouping(null, null);

    static Grouping of(String groupBy) {
      return switch (groupBy) {
        case "task", "type", "workflow" -> new Grouping(groupBy, null);
        default ->
            (groupBy.startsWith("param:") && groupBy.length() > "param:".length())
                ? new Grouping("param", groupBy.substring("param:".length()))
                : new Grouping("label", labelKey("groupBy", groupBy));
      };
    }

    // The task run's value for this grouping; null when it has none, and for every task run when
    // ungrouped.
    String keyOf(
        Document sample,
        Map<String, Object> params,
        WorkflowRunEntity run,
        Map<String, String> workflowNames) {
      if (kind == null) {
        return null;
      }
      return switch (kind) {
        case "task" -> sample.getString("taskName");
        case "type" -> sample.getString("type");
        case "workflow" -> workflowNames.getOrDefault(run.getWorkflowRef(), run.getWorkflowRef());
        case "param" -> keyText(params.get(name));
        default -> run.getLabels().get(name);
      };
    }
  }

  /** A row: the grouping's value and the bucket's start, either null. */
  private record TallyKey(String key, Date bucket) {}

  /** One row's task runs as they stream past. */
  private static final class RowTally {
    private final boolean spanning;
    private long taskRuns;
    private long succeeded;
    private long failed;
    private long cancelled;
    private final List<Long> durations = new ArrayList<>();
    // Per value of the spanBy label: the first start and the last end.
    private final Map<String, long[]> spans = new HashMap<>();

    RowTally(boolean spanning) {
      this.spanning = spanning;
    }

    void add(Document sample, String spanValue) {
      taskRuns++;
      RunStatus status = RunStatus.getRunStatus(sample.getString("status"));
      if (status == RunStatus.succeeded) {
        succeeded++;
        durations.add(durationOf(sample));
      } else if (UNSUCCESSFUL.contains(status)) {
        failed++;
      } else if (status == RunStatus.cancelled) {
        cancelled++;
      }
      Date start = sample.getDate("startTime");
      if (spanValue != null && start != null) {
        long[] span = {start.getTime(), start.getTime() + durationOf(sample)};
        spans.merge(
            spanValue,
            span,
            (a, b) -> new long[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
      }
    }

    Row row(TallyKey key) {
      Row row = new Row();
      row.setKey(key.key());
      row.setBucket(key.bucket());
      row.setTaskRuns(taskRuns);
      row.setSucceeded(succeeded);
      row.setFailed(failed);
      row.setCancelled(cancelled);
      row.setMeanDuration(
          Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0)));
      row.setP50Duration(percentile(durations, 0.5));
      row.setP95Duration(percentile(durations, 0.95));
      row.setMaxDuration(durations.stream().mapToLong(Long::longValue).max().orElse(0));
      if (spanning) {
        List<Long> lengths = spans.values().stream().map(span -> span[1] - span[0]).toList();
        row.setSpans((long) lengths.size());
        row.setP50Span(percentile(lengths, 0.5));
        row.setP95Span(percentile(lengths, 0.95));
        row.setMaxSpan(lengths.stream().mapToLong(Long::longValue).max().orElse(0));
      }
      return row;
    }
  }

  // label:<key> names a run label; anything else is refused as the named filter.
  private static String labelKey(String filter, String value) {
    if (!value.startsWith("label:") || value.length() == "label:".length()) {
      throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, filter);
    }
    return value.substring("label:".length());
  }

  private static ChronoUnit bucketUnit(String bucket) {
    return switch (bucket) {
      case "hour" -> ChronoUnit.HOURS;
      case "day" -> ChronoUnit.DAYS;
      case "month" -> ChronoUnit.MONTHS;
      default -> throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "bucket");
    };
  }

  /** The start, in UTC, of the hour, day or month holding the instant. */
  static Date bucketStart(Date instant, ChronoUnit unit) {
    ZonedDateTime utc = instant.toInstant().atZone(ZoneOffset.UTC);
    ZonedDateTime start =
        (unit == ChronoUnit.MONTHS)
            ? utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            : utc.truncatedTo(unit);
    return Date.from(start.toInstant());
  }

  private static long durationOf(Document sample) {
    return (sample.get("duration") instanceof Number duration) ? duration.longValue() : 0;
  }

  // The sample's params by name: only those asked for, and never a secret one.
  private static Map<String, Object> paramValues(Document sample) {
    Map<String, Object> values = new LinkedHashMap<>();
    List<Document> params = sample.getList("params", Document.class);
    if (params != null) {
      params.forEach(param -> values.putIfAbsent(param.getString("name"), param.get("value")));
    }
    return values;
  }

  // A param value as a group key: text as it is, an object as its JSON.
  private static String keyText(Object value) {
    if (value == null) {
      return null;
    }
    return (value instanceof Document document) ? document.toJson() : String.valueOf(value);
  }

  private static SlowTaskRun slowTaskRun(
      Document sample, Map<String, WorkflowRunEntity> runs, List<String> shown) {
    SlowTaskRun slow = new SlowTaskRun();
    slow.setTaskRunRef(String.valueOf(sample.get("_id")));
    slow.setWorkflowRunRef(sample.getString("workflowRunRef"));
    slow.setWorkflowRef(runs.get(slow.getWorkflowRunRef()).getWorkflowRef());
    slow.setName(sample.getString("name"));
    slow.setStatus(sample.getString("status"));
    slow.setStartTime(sample.getDate("startTime"));
    slow.setDuration(durationOf(sample));
    Map<String, Object> values = paramValues(sample);
    shown.stream()
        .filter(values::containsKey)
        .forEach(name -> slow.getParams().put(name, values.get(name)));
    return slow;
  }

  private static String firstText(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
