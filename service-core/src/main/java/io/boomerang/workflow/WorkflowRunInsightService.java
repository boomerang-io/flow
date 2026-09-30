package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightSummary.Day;
import io.boomerang.common.model.WorkflowRunInsightSummary.Totals;
import io.boomerang.common.model.WorkflowRunInsightSummary.WorkflowRow;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail.Failure;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail.TaskRow;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Run statistics for the Insights page, computed from the WorkflowRuns and TaskRuns a workspace
 * still holds. A deleted Workflow's runs go with it and are not counted here; the audit-trail
 * roll-up in {@code workspace.InsightsService} keeps serving the monthly quotas, which must count
 * them. Every read is a projection of the fields the statistics need, scoped to the workspace's
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

  private final RelationshipService relationshipService;
  private final WorkflowRepository workflowRepository;
  private final MongoTemplate mongoTemplate;

  public WorkflowRunInsightService(
      RelationshipService relationshipService,
      WorkflowRepository workflowRepository,
      MongoTemplate mongoTemplate) {
    this.relationshipService = relationshipService;
    this.workflowRepository = workflowRepository;
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Summarise the workspace's runs created in {@code [from, to)}, with the same-length period
   * before it for comparison. {@code workflows} are names or ids; {@code statuses} and {@code
   * triggers} narrow the runs counted in both periods.
   */
  public WorkflowRunInsightSummary summary(
      String workspace,
      Date from,
      Date to,
      Optional<List<String>> workflows,
      Optional<List<String>> statuses,
      Optional<List<String>> triggers) {
    List<String> workflowRefs = workflowRefs(workspace, workflows);
    Date previousFrom = new Date(from.getTime() - Math.max(to.getTime() - from.getTime(), 0));
    List<WorkflowRunEntity> current = new ArrayList<>();
    List<WorkflowRunEntity> previous = new ArrayList<>();
    for (WorkflowRunEntity run : runs(workflowRefs, previousFrom, to, statuses, triggers)) {
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
    return summary;
  }

  /** Detail one Workflow (by name or id) over {@code [from, to)}: task timings and failures. */
  public WorkflowRunInsightWorkflowDetail workflowDetail(
      String workspace, String workflow, Date from, Date to) {
    List<String> workflowRefs = workflowRefs(workspace, Optional.of(List.of(workflow)));
    if (workflowRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    String workflowRef = workflowRefs.get(0);
    List<WorkflowRunEntity> runs =
        runs(List.of(workflowRef), from, to, Optional.empty(), Optional.empty());
    List<TaskRunEntity> taskRuns = taskRuns(runs.stream().map(WorkflowRunEntity::getId).toList());

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
    detail.setTasks(taskRows(taskRuns));
    detail.setFailures(failures(runs, taskRuns));
    return detail;
  }

  // ── Reads ────────────────────────────────────────────────────────────────

  private List<String> workflowRefs(String workspace, Optional<List<String>> workflows) {
    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            workflows,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(workspace)),
            false);
    // A workspace the caller cannot reach is a 404; a reachable one with nothing to count is empty.
    if (refs.isEmpty()
        && !relationshipService.check(
            RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    return refs;
  }

  private List<WorkflowRunEntity> runs(
      List<String> workflowRefs,
      Date from,
      Date to,
      Optional<List<String>> statuses,
      Optional<List<String>> triggers) {
    if (workflowRefs.isEmpty()) {
      return List.of();
    }
    Query query =
        new Query(
            Criteria.where("workflowRef").in(workflowRefs).and("creationDate").gte(from).lt(to));
    statuses.ifPresent(list -> query.addCriteria(Criteria.where("status").in(list)));
    triggers.ifPresent(list -> query.addCriteria(Criteria.where("trigger").in(list)));
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

  private static String firstText(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
