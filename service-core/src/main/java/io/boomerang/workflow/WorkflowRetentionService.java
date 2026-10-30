package io.boomerang.workflow;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.util.SweepRunner;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.EngineConstants;
import io.boomerang.engine.TaskExecutionService;
import io.boomerang.engine.WorkflowExecutionService;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.schedule.repository.WorkflowScheduleRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Removes finished runs past the Activity retention, and deleted Workflows once their runs have
 * finished. The engine watcher calls both on its schedule. Every delete is by ref and idempotent,
 * so a pass that dies halfway is finished by the next one and overlapping instances are harmless.
 * Audit records are never touched: they are the record that outlives the deletion.
 */
@Service
public class WorkflowRetentionService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final int PAGE_SIZE = EngineConstants.SWEEP_PAGE_SIZE;

  private static final List<RunPhase> IN_FLIGHT_PHASES =
      List.of(RunPhase.pending, RunPhase.queued, RunPhase.running);

  // Days a finished run is kept: the "retention.days" key of the "workflowrun" settings document,
  // 365 when the document predates the key, never below 60 so a month's quota counts stay whole.
  private static final String RUN_RETENTION_KEY = "retention.days";
  private static final int RUN_RETENTION_DEFAULT_DAYS = 365;
  private static final int RUN_RETENTION_MIN_DAYS = 60;

  // The scheduler is single-threaded, so one retention pass is bounded to keep the recovery
  // sweeps on time; a backlog drains over several passes.
  private static final int MAX_PURGE_PAGES = 20;

  // A requested purge waits for the run's tasks to settle - a cancel ends them off the caller's
  // thread, and a dispatcher releases a terminated task on its next poll - so a pod is told to
  // stop before its order disappears; past this grace the run goes regardless.
  public static final long PURGE_RELEASE_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10);

  private static final List<RunStatus> TERMINATED = List.of(RunStatus.cancelled, RunStatus.timedout);

  /** The runs one purge request removed now and the ones it left to the sweep. */
  public record PurgeOutcome(List<String> purged, List<String> scheduled) {}

  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRepository workflowRepository;
  private final WorkflowRevisionRepository workflowRevisionRepository;
  private final TaskRunRepository taskRunRepository;
  private final ActionRepository actionRepository;
  private final WorkflowScheduleRepository scheduleRepository;
  private final RelationshipService relationshipService;
  private final ArtifactService artifactService;
  private final SettingsService settingsService;
  private final WorkflowService workflowService;
  private final WorkflowExecutionService workflowExecutionService;
  private final WorkflowRunStateHelper workflowRunStateHelper;
  private final TaskExecutionService taskExecutionService;

  public WorkflowRetentionService(
      WorkflowRunRepository workflowRunRepository,
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      TaskRunRepository taskRunRepository,
      ActionRepository actionRepository,
      WorkflowScheduleRepository scheduleRepository,
      RelationshipService relationshipService,
      ArtifactService artifactService,
      SettingsService settingsService,
      WorkflowService workflowService,
      @Lazy WorkflowExecutionService workflowExecutionService,
      WorkflowRunStateHelper workflowRunStateHelper,
      @Lazy TaskExecutionService taskExecutionService) {
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.taskRunRepository = taskRunRepository;
    this.actionRepository = actionRepository;
    this.scheduleRepository = scheduleRepository;
    this.relationshipService = relationshipService;
    this.artifactService = artifactService;
    this.settingsService = settingsService;
    this.workflowService = workflowService;
    this.workflowExecutionService = workflowExecutionService;
    this.workflowRunStateHelper = workflowRunStateHelper;
    this.taskExecutionService = taskExecutionService;
  }

  /**
   * Purge one run on request, with its lineage: the retries that point at it and the children its
   * task runs started, found through {@code initiatedByRef} and removed deepest first so no run
   * outlives one that points at it. A run still in flight is cancelled first. A run is removed now
   * when its tasks have settled; otherwise it is stamped for the purge sweep, which removes it once
   * every task has ended and the dispatcher's terminate poll has released the ones it held, or
   * after the grace. A run that does not exist is nothing to do.
   */
  public PurgeOutcome purge(String wfRunId) {
    List<String> purged = new ArrayList<>();
    List<String> scheduled = new ArrayList<>();
    List<WorkflowRunEntity> lineage = lineageOf(wfRunId);
    for (int i = lineage.size() - 1; i >= 0; i--) {
      WorkflowRunEntity wfRun = lineage.get(i);
      if (RunPhase.completed != wfRun.getPhase()) {
        workflowRunStateHelper.tryRequestPurge(wfRun.getId());
        workflowExecutionService.abort(wfRun.getId());
        LOGGER.info("[{}] Purge requested on a run in flight. Ended.", wfRun.getId());
      }
      if (unsettled(wfRun.getId())) {
        workflowRunStateHelper.tryRequestPurge(wfRun.getId());
        scheduled.add(wfRun.getId());
        LOGGER.info("[{}] The run's tasks have not settled. Purged by the sweep.", wfRun.getId());
        continue;
      }
      purgeRun(wfRun.getId());
      purged.add(wfRun.getId());
      LOGGER.info("[{}] Purged on request.", wfRun.getId());
    }
    return new PurgeOutcome(purged, scheduled);
  }

  /**
   * Remove the runs a purge was requested for whose tasks have settled, and any whose request is
   * older than the grace. A run found in flight again (its cancel lost to a transition) is
   * cancelled again. One page a sweep.
   */
  public int purgeRequestedRuns() {
    Date graceCutoff = new Date(System.currentTimeMillis() - PURGE_RELEASE_GRACE_MILLIS);
    int purged = 0;
    for (WorkflowRunEntity wfRun :
        workflowRunRepository.findByPurgeRequestedAtNotNullOrderByPurgeRequestedAtAsc(
            PageRequest.of(0, PAGE_SIZE))) {
      try {
        if (RunPhase.completed != wfRun.getPhase()) {
          workflowExecutionService.abort(wfRun.getId());
          continue;
        }
        if (unsettled(wfRun.getId()) && wfRun.getPurgeRequestedAt().after(graceCutoff)) {
          continue;
        }
        purgeRun(wfRun.getId());
        purged++;
        LOGGER.info("[{}] Purged as requested.", wfRun.getId());
      } catch (RuntimeException ex) {
        LOGGER.error("[{}] Requested purge failed: {}", wfRun.getId(), ex.getMessage());
      }
    }
    return purged;
  }

  // The run and every run that points at it or at one of its task runs, transitively, in
  // discovery order: a run before its retries and children. Empty when the run does not exist.
  private List<WorkflowRunEntity> lineageOf(String wfRunId) {
    Set<String> seen = new LinkedHashSet<>();
    List<WorkflowRunEntity> lineage = new ArrayList<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add(wfRunId);
    while (!pending.isEmpty()) {
      String id = pending.poll();
      if (!seen.add(id)) {
        continue;
      }
      WorkflowRunEntity wfRun = workflowRunRepository.findById(id).orElse(null);
      if (wfRun == null) {
        continue;
      }
      lineage.add(wfRun);
      List<String> refs = new ArrayList<>();
      refs.add(id);
      taskRunRepository.findIdsByWorkflowRunRef(id).forEach(task -> refs.add(task.getId()));
      workflowRunRepository
          .findByInitiatedByRefIn(refs)
          .forEach(pointing -> pending.add(pointing.getId()));
    }
    return lineage;
  }

  private boolean unsettled(String wfRunId) {
    return taskRunRepository.existsUnsettledTask(wfRunId, TERMINATED);
  }

  /**
   * Purge finished runs older than the Activity retention: each run's run- and log-scoped
   * artifacts (files first), actions, task runs, relationship node and finally the run document.
   * A run another run still points at through {@code initiatedByRef} - by the run's id (a retry's
   * origin) or by one of its task runs' ids (a child's parent) - is kept until that run is gone,
   * so a carried-over task run never outlives the task run it stands for and a child's "started
   * by" link never dangles. In-flight runs, workflow-scoped artifacts and audit records are never
   * touched.
   */
  public void purgeExpiredRuns() {
    Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(runRetentionDays()));
    Date after = new Date(0);
    int purged = 0;
    for (int pages = 0; pages < MAX_PURGE_PAGES; pages++) {
      List<WorkflowRunEntity> page =
          workflowRunRepository.findByPhaseAndCreationDateBetween(
              RunPhase.completed,
              after,
              cutoff,
              PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "creationDate")));
      for (WorkflowRunEntity wfRun : page) {
        if (isPointedAt(wfRun.getId())) {
          LOGGER.debug("[{}] Kept past retention: a later run points at it.", wfRun.getId());
          continue;
        }
        try {
          purgeRun(wfRun.getId());
          purged++;
        } catch (RuntimeException ex) {
          LOGGER.error("[{}] Retention purge failed: {}", wfRun.getId(), ex.getMessage());
        }
      }
      if (page.size() < PAGE_SIZE) {
        break;
      }
      after = page.get(page.size() - 1).getCreationDate();
    }
    if (purged > 0) {
      LOGGER.info("Retention purged {} run(s) created before {}.", purged, cutoff);
    }
  }

  /**
   * Prune deleted (tombstoned) Workflows whose runs have all completed: purge each run the way
   * retention does, then hard-delete the workflow's artifacts, revisions, leftover task runs,
   * schedules, actions and relationship node, and finally the workflow document itself. A
   * workflow with a run still in flight is skipped - the watcher's cancel sweep finishes it first,
   * so live work is never pruned.
   */
  public void pruneDeletedWorkflows() {
    SweepRunner.forEachIsolated(
        workflowRepository.findByStatus(WorkflowStatus.deleted, PageRequest.of(0, PAGE_SIZE)),
        workflow -> {
          if (!workflowRunRepository
              .findByWorkflowRefAndPhaseIn(workflow.getId(), IN_FLIGHT_PHASES)
              .isEmpty()) {
            return;
          }
          // Each purge deletes its run, so the first page advances until the workflow has none.
          List<WorkflowRunEntity> runs;
          while (!(runs =
                  workflowRunRepository.findByWorkflowRef(
                      workflow.getId(), PageRequest.of(0, PAGE_SIZE)))
              .isEmpty()) {
            runs.forEach(run -> purgeRun(run.getId()));
          }
          artifactService.deleteForWorkflow(workflow.getId(), PAGE_SIZE);
          // Catch-all for task runs whose run document is already gone.
          taskRunRepository.deleteByWorkflowRef(workflow.getId());
          workflowRevisionRepository.deleteByWorkflowRef(workflow.getId());
          scheduleRepository.deleteByWorkflowRef(workflow.getId());
          workflowService.removeWorkflowReferences(workflow.getId());
          workflowRepository.deleteById(workflow.getId());
          LOGGER.info(
              "[{}] Pruned deleted Workflow: its runs with their relationship nodes, artifacts,"
                  + " revisions, actions, schedules and its own node are removed; audit records"
                  + " are kept.",
              workflow.getId());
        },
        (workflow, ex) ->
            LOGGER.error(
                "[{}] Deleted-workflow prune failed: {}", workflow.getId(), ex.getMessage()));
  }

  /** Days a finished run is kept: the setting, 365 when absent or not a number, never below 60. */
  public int runRetentionDays() {
    try {
      String value =
          settingsService
              .getSettingConfig(TaskExecutionService.WORKFLOWRUN_SETTINGS_KEY, RUN_RETENTION_KEY)
              .getValue();
      return Math.max(RUN_RETENTION_MIN_DAYS, Integer.parseInt(value.trim()));
    } catch (RuntimeException notConfigured) {
      return RUN_RETENTION_DEFAULT_DAYS;
    }
  }

  // A retry points at its origin run; a child points at the parent's task run that submitted it.
  private boolean isPointedAt(String wfRunId) {
    return workflowRunRepository.existsByInitiatedByRef(wfRunId)
        || workflowRunRepository.existsByInitiatedByRefIn(
            taskRunRepository.findIdsByWorkflowRunRef(wfRunId).stream()
                .map(TaskRunEntity::getId)
                .toList());
  }

  private void purgeRun(String wfRunId) {
    taskExecutionService.releaseLocksHeldBy(wfRunId);
    artifactService.deleteForRun(wfRunId);
    actionRepository.deleteByWorkflowRunRef(wfRunId);
    taskRunRepository.deleteByWorkflowRunRef(wfRunId);
    relationshipService.removeNodeAndEdgeByRef(RelationshipType.WORKFLOWRUN, wfRunId);
    workflowRunRepository.deleteById(wfRunId);
    LOGGER.debug("[{}] Purged past retention.", wfRunId);
  }
}
