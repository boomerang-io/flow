package io.boomerang.workflow;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.WorkflowConcurrency;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.common.util.StorageQuantityUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.UnmeteredWorkflows;
import io.boomerang.core.audit.AuditQueryService;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * A workspace's quotas: the limits (the platform defaults in the {@code quotas} settings, with the
 * workspace's own stored values over them), what the workspace currently uses, and the checks that
 * refuse work over a limit with {@code QUOTA_EXCEEDED}. The {@code workspaceQuotas} feature setting
 * is the one switch for enforcement. Counts are read straight from the stores, so this depends on
 * no other feature service.
 */
@Service
public class QuotaService {

  private static final Logger LOGGER = LogManager.getLogger();

  public static final String QUOTAS_SETTINGS_KEY = "quotas";
  public static final String QUOTA_MAX_WORKFLOW_COUNT = "max.workflow.count";
  public static final String QUOTA_MAX_WORKFLOWRUN_CONCURRENT = "max.workflowrun.concurrent";
  public static final String QUOTA_MAX_WORKFLOWRUN_QUEUED = "max.workflowrun.queued";
  public static final String QUOTA_MAX_WORKFLOWRUN_MONTHLY = "max.workflowrun.monthly";
  public static final String QUOTA_MAX_WORKFLOWRUN_DURATION = "max.workflowrun.duration";
  public static final String QUOTA_MAX_WORKFLOWRUN_CUMULATIVE =
      "max.workflowrun.cumulative.duration";
  public static final String QUOTA_MAX_WORKSPACE_STORAGE = "max.workspace.storage";
  public static final String QUOTA_MAX_ARTIFACT_STORAGE = "max.artifact.storage";

  // What a quota refusal names as exceeded, the first argument of QUOTA_EXCEEDED.
  public static final String QUEUED_RUNS = "Queued runs";
  public static final String MONTHLY_RUNS = "Number of runs (executions)";
  public static final String CUMULATIVE_DURATION = "Cumulative duration this month (minutes)";
  public static final String WORKSPACE_SIZE = "Requested Workspace size";

  // A run holds a slot of the concurrent limit once admitted, until it completes; one in its
  // finally stage is counted apart, by phase. A run waiting for a slot holds none: it is queued.
  private static final Set<RunStatus> IN_FLIGHT_STATUSES = Set.of(RunStatus.ready, RunStatus.running);

  // Artifacts that hold storage; task logs are kept apart and not counted.
  private static final List<ArtifactStatus> HOLDING_STORAGE =
      List.of(ArtifactStatus.uploading, ArtifactStatus.available);

  private final SettingsService settingsService;
  private final WorkspaceRepository workspaceRepository;
  private final RelationshipService relationshipService;
  private final AuditQueryService auditQueryService;
  private final UnmeteredWorkflows unmeteredWorkflows;
  private final MongoTemplate mongoTemplate;

  public QuotaService(
      SettingsService settingsService,
      WorkspaceRepository workspaceRepository,
      RelationshipService relationshipService,
      AuditQueryService auditQueryService,
      UnmeteredWorkflows unmeteredWorkflows,
      MongoTemplate mongoTemplate) {
    this.settingsService = settingsService;
    this.workspaceRepository = workspaceRepository;
    this.relationshipService = relationshipService;
    this.auditQueryService = auditQueryService;
    this.unmeteredWorkflows = unmeteredWorkflows;
    this.mongoTemplate = mongoTemplate;
  }

  // ── Limits ────────────────────────────────────────────────────────────────

  /** Whether quota limits are enforced: the stored "workspaceQuotas" feature setting. */
  public boolean enforced() {
    return settingsService
        .getSettingConfig(
            WorkflowService.FEATURES_SETTINGS_KEY, WorkflowService.FEATURES_WORKSPACE_QUOTA)
        .getBooleanValue();
  }

  /** The platform defaults, read from the settings each time - never stored on a workspace. */
  public Quotas defaults() {
    return defaults(quotaSettings());
  }

  // The platform defaults from the quotas settings document's entries, read once.
  private Quotas defaults(Map<String, String> settings) {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowCount(Integer.valueOf(settings.get(QUOTA_MAX_WORKFLOW_COUNT).trim()));
    quotas.setMaxWorkflowRunMonthly(
        Integer.valueOf(settings.get(QUOTA_MAX_WORKFLOWRUN_MONTHLY).trim()));
    quotas.setMaxCumulativeDuration(
        cumulativeDefault(settings.get(QUOTA_MAX_WORKFLOWRUN_CUMULATIVE)));
    quotas.setMaxWorkspaceStorage(
        Integer.valueOf(settings.get(QUOTA_MAX_WORKSPACE_STORAGE).replace("Gi", "").trim()));
    quotas.setMaxWorkflowRunDuration(
        Integer.valueOf(settings.get(QUOTA_MAX_WORKFLOWRUN_DURATION).trim()));
    quotas.setMaxConcurrentRuns(
        Integer.valueOf(settings.get(QUOTA_MAX_WORKFLOWRUN_CONCURRENT).trim()));
    quotas.setMaxQueuedRuns(Integer.valueOf(settings.get(QUOTA_MAX_WORKFLOWRUN_QUEUED).trim()));
    quotas.setMaxArtifactStorage(
        Integer.valueOf(settings.get(QUOTA_MAX_ARTIFACT_STORAGE).replace("Gi", "").trim()));
    quotas.setArtifactRetentionDays(defaultArtifactRetentionDays());
    return quotas;
  }

  /** The platform's shared-workspace size limit as written in the settings, unit included. */
  public String defaultWorkspaceStorage() {
    return setting(QUOTA_MAX_WORKSPACE_STORAGE);
  }

  /**
   * The ceiling on the shared-workspace size a Workflow may declare, in Gi: the workspace's own
   * stored quota when it has one - a stored 0 is no ceiling ({@link Quotas#limited}) - else the
   * platform default. The same limit a run submission is held to.
   */
  public Integer maxWorkspaceStorage(String workspace) {
    Integer stored =
        workspaceRepository
            .findByIdOrName(workspace)
            .map(WorkspaceEntity::getQuotas)
            .map(Quotas::getMaxWorkspaceStorage)
            .orElse(null);
    return (stored != null)
        ? stored
        : Integer.valueOf(setting(QUOTA_MAX_WORKSPACE_STORAGE).replace("Gi", ""));
  }

  /** The platform's run-duration limit in minutes; throws when the setting is missing. */
  public long defaultMaxRunDuration() {
    return Long.parseLong(setting(QUOTA_MAX_WORKFLOWRUN_DURATION));
  }

  /** Days a workspace's artifacts are kept unless its quota says otherwise, at most the maximum. */
  public int defaultArtifactRetentionDays() {
    return Math.min(
        artifactSetting(ArtifactService.RETENTION_DEFAULT_DAYS),
        artifactSetting(ArtifactService.RETENTION_MAX_DAYS));
  }

  /**
   * Overlay the values {@code overrides} sets onto {@code quotas}: an absent value keeps what is
   * there, a set value replaces it - a 0 included, since 0 is unlimited ({@link Quotas#limited}).
   * The platform defaults are overlaid with a workspace's stored quotas this way, and a workspace's
   * stored quotas with a create or patch request's.
   */
  public static void overlay(Quotas quotas, Quotas overrides) {
    if (overrides == null) {
      return;
    }
    if (overrides.getMaxWorkflowCount() != null) {
      quotas.setMaxWorkflowCount(overrides.getMaxWorkflowCount());
    }
    if (overrides.getMaxWorkflowRunMonthly() != null) {
      quotas.setMaxWorkflowRunMonthly(overrides.getMaxWorkflowRunMonthly());
    }
    if (overrides.getMaxCumulativeDuration() != null) {
      quotas.setMaxCumulativeDuration(overrides.getMaxCumulativeDuration());
    }
    if (overrides.getMaxWorkspaceStorage() != null) {
      quotas.setMaxWorkspaceStorage(overrides.getMaxWorkspaceStorage());
    }
    if (overrides.getMaxWorkflowRunDuration() != null) {
      quotas.setMaxWorkflowRunDuration(overrides.getMaxWorkflowRunDuration());
    }
    if (overrides.getMaxConcurrentRuns() != null) {
      quotas.setMaxConcurrentRuns(overrides.getMaxConcurrentRuns());
    }
    if (overrides.getMaxQueuedRuns() != null) {
      quotas.setMaxQueuedRuns(overrides.getMaxQueuedRuns());
    }
    if (overrides.getMaxArtifactStorage() != null) {
      quotas.setMaxArtifactStorage(overrides.getMaxArtifactStorage());
    }
    if (overrides.getArtifactRetentionDays() != null) {
      quotas.setArtifactRetentionDays(overrides.getArtifactRetentionDays());
    }
  }

  /** A workspace's limits: the platform defaults with its own stored quotas over them. */
  public Quotas limits(WorkspaceEntity workspace) {
    Quotas quotas = defaults();
    overlay(quotas, workspace.getQuotas());
    return quotas;
  }

  /**
   * The ceiling for a WorkflowRun's timeout, in minutes: the workspace's own stored run duration
   * when it has one - a stored 0 is no ceiling - else the platform default. 0 for a Workflow no
   * workspace owns: only the floor and the platform default constrain those runs.
   */
  public long maxRunDuration(String workspace) {
    if (workspace == null) {
      return 0;
    }
    Integer stored =
        workspaceRepository
            .findByIdOrName(workspace)
            .map(WorkspaceEntity::getQuotas)
            .map(Quotas::getMaxWorkflowRunDuration)
            .orElse(null);
    return (stored != null) ? stored : intSetting(QUOTA_MAX_WORKFLOWRUN_DURATION);
  }

  // ── Usage ─────────────────────────────────────────────────────────────────

  /**
   * A workspace's limits with its current usage filled in, the workspace given by id or name;
   * null when none matches.
   */
  public CurrentQuotas current(String workspace) {
    return workspaceRepository.findByIdOrName(workspace).map(this::current).orElse(null);
  }

  /**
   * A workspace's limits with its current usage filled in. Monthly figures cover the current UTC
   * calendar month, and the workflows counted are the ones the workspace owns.
   */
  public CurrentQuotas current(WorkspaceEntity entity) {
    return current(List.of(entity)).get(entity.getName());
  }

  /**
   * {@link #current(WorkspaceEntity)} for several workspaces, keyed by workspace name, in a fixed
   * number of queries however many there are: each count is one query grouped by workflow, summed
   * per workspace here.
   */
  public Map<String, CurrentQuotas> current(Collection<WorkspaceEntity> entities) {
    Map<String, CurrentQuotas> current = new LinkedHashMap<>();
    if (entities.isEmpty()) {
      return current;
    }
    // Counted under each workspace's own name, the key its audit events and graph node carry,
    // whatever form the caller named it in.
    List<String> workspaces = entities.stream().map(WorkspaceEntity::getName).toList();
    Map<String, String> settings = quotaSettings();
    Quotas defaults = defaults(settings);
    MonthWindow month = MonthWindow.of(LocalDate.now(ZoneOffset.UTC));

    // Milliseconds, like every run duration: this month's total and the mean per run that ended,
    // from the audit trail so runs of since-deleted Workflows still count; and the month's runs
    // and cumulative minutes, the unmetered workflows' left out.
    Map<String, AuditQueryService.RunTotals> runTotals =
        auditQueryService.runTotals(workspaces, month.from(), month.to());
    Map<String, AuditQueryService.MonthlyUsage> monthlyUsage =
        auditQueryService.monthlyUsage(workspaces, month.from(), month.to());

    // The workspaces' workflows by their own edges: a quota is the workspace's whoever asks, and
    // the engine asks with no identity when it adopts a template link for a child run.
    Map<String, List<String>> workflowRefs = new HashMap<>();
    relationshipService
        .childrenByLabel(RelationshipType.WORKSPACE, workspaces, RelationshipLabel.HAS_WORKFLOW)
        .forEach(
            (workspace, nodes) ->
                workflowRefs.put(
                    workspace, nodes.stream().map(RelationshipNodeEntity::getRef).toList()));
    List<String> allWorkflowRefs = workflowRefs.values().stream().flatMap(List::stream).toList();
    Map<String, Long> liveMonthly =
        countRuns(
            allWorkflowRefs, Optional.of(month.from()), Optional.of(month.to()), Optional.empty());
    Map<String, Long> refusedMonthly = countRefusedSubmits(allWorkflowRefs, month);
    Map<String, Long> concurrent =
        countRuns(
            allWorkflowRefs, Optional.empty(), Optional.empty(), Optional.of(IN_FLIGHT_STATUSES));
    Map<String, Long> queued =
        allWorkflowRefs.isEmpty()
            ? Map.of()
            : countByWorkflow(WorkflowRunEntity.class, queuedForWorkspace(allWorkflowRefs, false));
    Map<String, Long> finallyStage = countFinallyStage(allWorkflowRefs);
    Map<String, Long> stored = storedBytes(allWorkflowRefs);
    Set<String> liveWorkflows = liveWorkflows(allWorkflowRefs);
    // The workflows whose runs use none of the month, left out of the live count: two reads, and
    // none when no workflow is unmetered.
    Set<String> unmetered =
        unmeteredWorkflows.unmeteredRefs(
            workflowRefs, UnmeteredWorkflows.parse(settings.get(UnmeteredWorkflows.SETTING_KEY)));

    for (WorkspaceEntity entity : entities) {
      String workspace = entity.getName();
      List<String> refs = workflowRefs.getOrDefault(workspace, List.of());
      CurrentQuotas quotas = new CurrentQuotas(defaults);
      overlay(quotas, entity.getQuotas());
      quotas.setMonthlyResetDate(month.to());
      AuditQueryService.RunTotals totals =
          runTotals.getOrDefault(workspace, new AuditQueryService.RunTotals(0L, 0L, 0L));
      quotas.setCurrentRunTotalDuration(totals.duration());
      // Only an ended run has a duration, so the mean is over the runs that ended.
      quotas.setCurrentRunAverageDuration(
          (totals.endedRuns() > 0) ? totals.duration() / totals.endedRuns() : 0L);
      AuditQueryService.MonthlyUsage usage =
          monthlyUsage.getOrDefault(workspace, new AuditQueryService.MonthlyUsage(0L, 0L));
      List<String> metered = refs.stream().filter(ref -> !unmetered.contains(ref)).toList();
      // Monthly = max(audit count, live count), deliberately: the audit write is async best-effort,
      // so a just-admitted run may not be audited yet (the live count covers that race), while a
      // deleted Workflow's runs leave the live count (the audit count covers deletion - the CREATE
      // events outlive both the run documents and the Workflow). The entry a refused submit left
      // is a run document that was never admitted, so the live count leaves it out; it has no
      // CREATE event. An unmetered workflow's runs are in neither.
      quotas.setCurrentRuns(
          (int)
              Math.max(
                  usage.runs(), sum(liveMonthly, metered) - sum(refusedMonthly, metered)));
      quotas.setCurrentCumulativeDuration(usage.cumulativeMinutes());
      // Concurrent = holding a slot, from the live WorkflowRun collection: admitted or running,
      // or in the finally stage, which carries the final status while its finally tasks still
      // run. Queued = waiting for a slot, of the workspace or of its own workflow.
      quotas.setCurrentConcurrentRuns((int) (sum(concurrent, refs) + sum(finallyStage, refs)));
      quotas.setCurrentQueuedRuns((int) sum(queued, refs));
      quotas.setCurrentArtifactStorage(sum(stored, refs));
      quotas.setCurrentWorkflowCount((int) refs.stream().filter(liveWorkflows::contains).count());
      current.put(workspace, quotas);
    }
    return current;
  }

  /**
   * The workspace's limits with only its current artifact storage filled in, or null when no
   * workspace has the ref. It reads the relationship graph without a caller identity, so the
   * dispatcher protocol and the engine, which act for no user, can check an artifact against it.
   */
  public CurrentQuotas artifactStorage(String workspaceRef) {
    return workspaceRepository
        .findById(workspaceRef)
        .map(
            workspace -> {
              CurrentQuotas quotas = new CurrentQuotas(limits(workspace));
              List<String> workflowRefs =
                  relationshipService.findNodeRefs(
                      RelationshipType.WORKSPACE, workspaceRef, RelationshipType.WORKFLOW);
              quotas.setCurrentArtifactStorage(sum(storedBytes(workflowRefs), workflowRefs));
              return quotas;
            })
        .orElse(null);
  }

  // ── Checks ────────────────────────────────────────────────────────────────

  /** Refuses a new Workflow when the workspace is over its workflow count, when enforced. */
  public void assertCanCreateWorkflow(String workspace) {
    if (enforced()) {
      CurrentQuotas quotas = current(workspace);
      // A workflow no workspace owns has no workspace quota to hold it to.
      if (quotas == null) {
        return;
      }
      LOGGER.debug("Quotas: {}", quotas);
      // Checked before the create, so a workspace at its limit has no room for one more.
      if (Quotas.limited(quotas.getMaxWorkflowCount())
          && quotas.getCurrentWorkflowCount() >= quotas.getMaxWorkflowCount()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Number of Workflows",
            quotas.getCurrentWorkflowCount(),
            quotas.getMaxWorkflowCount());
      }
    }
  }

  /**
   * Refuses, with {@code QUOTA_EXCEEDED}, a run the workspace's quotas have no room for: runs this
   * month, cumulative duration this month, the size of any Workspace it asks for, and a full queue.
   * The concurrent limit alone never refuses: a run over it waits in the queue. The queue's cap
   * holds only the runs waiting for a slot of the workspace: a run of a workflow at its own
   * concurrency limit waits for that workflow, however long its queue, and is never refused by the
   * cap. A run of an unmetered workflow ({@link UnmeteredWorkflows}) is held to neither monthly
   * quota, which is asked only when one would refuse. A submit and a retry - a retry is a new run -
   * both clear it before the run is created. No quota ends a run already going: the cumulative
   * duration is counted as runs end, so a month can overshoot by the runs in flight.
   */
  public void assertRunQuotas(
      String workspace,
      Optional<WorkflowWorkspace> runWorkspace,
      String workflowRef,
      String workflowRevisionRef) {
    if (!enforced()) {
      return;
    }
    Admission admission = admission(workspace, false);
    // A workflow no workspace owns has no workspace quota to hold it to.
    if (admission == null) {
      return;
    }
    LOGGER.debug("Admission: {}", admission);
    // The run being submitted is not counted yet, so at-the-limit already means it would exceed
    // the limit.
    boolean monthSpent =
        Quotas.limited(admission.maxMonthly()) && admission.monthly() >= admission.maxMonthly();
    boolean minutesSpent =
        Quotas.limited(admission.maxCumulative())
            && admission.cumulative() >= admission.maxCumulative();
    if ((monthSpent || minutesSpent) && !unmetered(admission, workflowRef)) {
      throw monthSpent
          ? new BoomerangException(
              BoomerangError.QUOTA_EXCEEDED,
              MONTHLY_RUNS,
              admission.monthly(),
              admission.maxMonthly())
          : new BoomerangException(
              BoomerangError.QUOTA_EXCEEDED,
              CUMULATIVE_DURATION,
              admission.cumulative(),
              admission.maxCumulative());
    }
    if (runWorkspace.isPresent()) {
      assertWorkspaceStorage(runWorkspace.get(), admission.maxWorkspaceStorage());
    }
    // The queue is full only while every slot is taken: with a slot free the run would start.
    if (admission.queueLimited()
        && admission.inFlight() >= admission.maxConcurrent()
        && admission.queued() >= admission.maxQueued()
        && !heldByItsWorkflow(workflowRef, workflowRevisionRef)) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED, QUEUED_RUNS, admission.queued(), admission.maxQueued());
    }
  }

  /**
   * Refuses a run once it has been inserted when it pushed a count over its limit. The counts now
   * include the run itself, so a burst of simultaneous submits cannot each pass the pre-admission
   * count; the caller removes the run before passing the refusal on. With every slot taken, the
   * runs not yet admitted - this one among them - may not outnumber the queue's places. An
   * unmetered workflow's run is not held to the month's runs; the cumulative duration is not
   * rechecked, since a run adds to it only when it ends.
   */
  public void assertAdmittedRunWithinQuotas(
      String workspace, String workflowRef, String workflowRevisionRef) {
    Admission admission = admission(workspace, true);
    // A workflow no workspace owns has no workspace quota to hold it to.
    if (admission == null) {
      return;
    }
    if (Quotas.limited(admission.maxMonthly())
        && admission.monthly() > admission.maxMonthly()
        && !unmetered(admission, workflowRef)) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED, MONTHLY_RUNS, admission.monthly(), admission.maxMonthly());
    }
    if (admission.queueLimited()
        && admission.inFlight() >= admission.maxConcurrent()
        && admission.queued() > admission.maxQueued()
        && !heldByItsWorkflow(workflowRef, workflowRevisionRef)) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED, QUEUED_RUNS, admission.queued(), admission.maxQueued());
    }
  }

  // Whether the workflow's runs are unmetered under the names the admission read.
  private boolean unmetered(Admission admission, String workflowRef) {
    return unmeteredWorkflows.test(
        admission.unmeteredWorkflows(), admission.workspace(), null, workflowRef);
  }

  // Whether a run of the workflow would wait for the workflow's own concurrency limit, which its
  // admission checks first: the revision limits it and the workflow's runs in flight fill it. Read
  // only when the cap would otherwise refuse the run.
  private boolean heldByItsWorkflow(String workflowRef, String workflowRevisionRef) {
    if (workflowRef == null || workflowRevisionRef == null) {
      return false;
    }
    Query revision = Query.query(where("_id").is(workflowRevisionRef));
    revision.fields().include("concurrency");
    WorkflowRevisionEntity entity = mongoTemplate.findOne(revision, WorkflowRevisionEntity.class);
    WorkflowConcurrency concurrency = (entity != null) ? entity.getConcurrency() : null;
    return concurrency != null
        && concurrency.limits()
        && inFlight(List.of(workflowRef)) >= concurrency.getLimit();
  }

  /**
   * What a run's admission is checked against: the workspace, by name, its limits, the counts
   * behind them and the unmetered workflows' names. A count behind an unlimited quota is not read
   * and is 0; {@code cumulative} is in minutes.
   */
  public record Admission(
      String workspace,
      List<String> unmeteredWorkflows,
      int maxMonthly,
      int maxCumulative,
      int maxConcurrent,
      int maxQueued,
      Integer maxWorkspaceStorage,
      long monthly,
      long cumulative,
      long inFlight,
      long queued) {

    /** Whether a full queue can refuse a run: both the slots and the queue's places are limited. */
    boolean queueLimited() {
      return maxConcurrent > 0 && maxQueued > 0;
    }
  }

  /**
   * A workspace's admission limits and counts, the workspace given by id or name; null when none
   * matches. Each count is one count over index keys of the workspace's own workflows, so it costs
   * the same however many workflows and runs there are - unlike {@link #current(String)}, which
   * also reads the audit totals, storage and workflow counts no admission needs. The {@code quotas}
   * settings document is read once for every key. {@code queued} counts the runs waiting for a
   * slot of the workspace, and with {@code withUnadmitted} the runs just inserted and not yet
   * admitted or parked as well; then the cumulative duration, which is not rechecked, is not read.
   */
  Admission admission(String workspace, boolean withUnadmitted) {
    WorkspaceEntity entity = workspaceRepository.findByIdOrName(workspace).orElse(null);
    if (entity == null) {
      return null;
    }
    Map<String, String> settings = quotaSettings();
    Quotas stored = (entity.getQuotas() != null) ? entity.getQuotas() : new Quotas();
    int maxMonthly =
        limitOrDefault(
            stored.getMaxWorkflowRunMonthly(), settings.get(QUOTA_MAX_WORKFLOWRUN_MONTHLY));
    int maxCumulative =
        (stored.getMaxCumulativeDuration() != null)
            ? stored.getMaxCumulativeDuration()
            : cumulativeDefault(settings.get(QUOTA_MAX_WORKFLOWRUN_CUMULATIVE));
    int maxConcurrent =
        limitOrDefault(
            stored.getMaxConcurrentRuns(), settings.get(QUOTA_MAX_WORKFLOWRUN_CONCURRENT));
    int maxQueued =
        limitOrDefault(stored.getMaxQueuedRuns(), settings.get(QUOTA_MAX_WORKFLOWRUN_QUEUED));
    Integer maxWorkspaceStorage =
        (stored.getMaxWorkspaceStorage() != null)
            ? stored.getMaxWorkspaceStorage()
            : Integer.valueOf(settings.get(QUOTA_MAX_WORKSPACE_STORAGE).replace("Gi", ""));
    List<String> unmetered =
        UnmeteredWorkflows.parse(settings.get(UnmeteredWorkflows.SETTING_KEY));
    boolean queueLimited = maxConcurrent > 0 && maxQueued > 0;
    List<String> workflowRefs =
        (maxMonthly > 0 || queueLimited) ? workflowRefs(entity.getId()) : List.of();
    long monthly = 0;
    long cumulative = 0;
    if (maxMonthly > 0 || (maxCumulative > 0 && !withUnadmitted)) {
      MonthWindow month = MonthWindow.of(LocalDate.now(ZoneOffset.UTC));
      AuditQueryService.MonthlyUsage usage =
          auditQueryService
              .monthlyUsage(List.of(entity.getName()), month.from(), month.to())
              .getOrDefault(entity.getName(), new AuditQueryService.MonthlyUsage(0L, 0L));
      cumulative = usage.cumulativeMinutes();
      if (maxMonthly > 0) {
        monthly = Math.max(usage.runs(), liveRunsIn(workflowRefs, month));
        // The live count holds the unmetered workflows' runs; it is recounted without them only
        // when it would refuse, so a run under the limit costs nothing more.
        if (monthly >= maxMonthly && !unmetered.isEmpty()) {
          Set<String> free =
              unmeteredWorkflows.unmeteredRefs(Map.of(entity.getName(), workflowRefs), unmetered);
          if (!free.isEmpty()) {
            List<String> metered =
                workflowRefs.stream().filter(ref -> !free.contains(ref)).toList();
            monthly = Math.max(usage.runs(), metered.isEmpty() ? 0 : liveRunsIn(metered, month));
          }
        }
      }
    }
    long inFlight = queueLimited ? inFlight(workflowRefs) : 0;
    long queued = queueLimited ? count(queuedForWorkspace(workflowRefs, withUnadmitted)) : 0;
    return new Admission(
        entity.getName(),
        unmetered,
        maxMonthly,
        maxCumulative,
        maxConcurrent,
        maxQueued,
        maxWorkspaceStorage,
        monthly,
        cumulative,
        inFlight,
        queued);
  }

  /**
   * The slots of the concurrent limit a run of the workflow is admitted against: the owning
   * workspace's workflows, its limit and the runs of those workflows holding a slot now. Null when
   * no slot is to be checked: quotas are not enforced, no workspace owns the workflow, or its
   * concurrent limit is unlimited. Reads the relationship graph without a caller identity, for the
   * engine, which admits for no user.
   */
  public RunSlots runSlots(String workflowRef) {
    if (!enforced()) {
      return null;
    }
    String workspaceRef =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef);
    if (workspaceRef == null || workspaceRef.isBlank()) {
      return null;
    }
    WorkspaceEntity entity = workspaceRepository.findById(workspaceRef).orElse(null);
    if (entity == null) {
      return null;
    }
    int limit =
        limit(
            (entity.getQuotas() != null) ? entity.getQuotas().getMaxConcurrentRuns() : null,
            QUOTA_MAX_WORKFLOWRUN_CONCURRENT);
    if (limit <= 0) {
      return null;
    }
    List<String> workflowRefs = workflowRefs(workspaceRef);
    return new RunSlots(workflowRefs, limit, inFlight(workflowRefs));
  }

  /** A workspace's workflows, its concurrent limit and the runs of those workflows in flight. */
  public record RunSlots(List<String> workflowRefs, int limit, long inFlight) {

    public boolean full() {
      return inFlight >= limit;
    }
  }

  // The workspace's own stored limit, else the platform default; 0 is unlimited.
  private int limit(Integer stored, String defaultKey) {
    return (stored != null) ? stored : intSetting(defaultKey);
  }

  // The workspace's own stored limit, else the platform default's value as written.
  private static int limitOrDefault(Integer stored, String defaultValue) {
    return (stored != null) ? stored : Integer.parseInt(defaultValue.trim());
  }

  // The cumulative duration's platform default: an install whose settings predate the key, or a
  // blank value, has no limit.
  private static int cumulativeDefault(String value) {
    return (value == null || value.isBlank()) ? 0 : Integer.parseInt(value.trim());
  }

  // Every entry of the quotas settings document by key, in one read.
  private Map<String, String> quotaSettings() {
    SettingEntity settings = settingsService.getSettingByKey(QUOTAS_SETTINGS_KEY);
    Map<String, String> values = new HashMap<>();
    for (SettingConfig config : settings.getConfig()) {
      if (config.getKey() != null && config.getValue() != null) {
        values.put(config.getKey(), config.getValue());
      }
    }
    return values;
  }

  // The workflows a workspace owns, from its edges alone: no workflow node is read.
  private List<String> workflowRefs(String workspaceRef) {
    String prefix = RelationshipType.WORKFLOW.getLabel() + ":";
    return relationshipService
        .childIdsByLabel(RelationshipType.WORKSPACE, workspaceRef, RelationshipLabel.HAS_WORKFLOW)
        .stream()
        .filter(id -> id.startsWith(prefix))
        .map(id -> id.substring(prefix.length()))
        .toList();
  }

  // Runs holding a slot: admitted or running, counted on workflow_ref_status, and the ones in
  // their finally stage, which carry their final status, counted on workflow_ref_phase.
  private long inFlight(List<String> workflowRefs) {
    return count(inStatuses(workflowRefs, IN_FLIGHT_STATUSES))
        + count(
            where("workflowRef")
                .in(workflowRefs)
                .and("phase")
                .is(RunPhase.running.name())
                .and("graphEndedAt")
                .exists(true));
  }

  // Runs created in the month, less the entries refused submits left, which were never admitted.
  private long liveRunsIn(List<String> workflowRefs, MonthWindow month) {
    Criteria created =
        where("workflowRef").in(workflowRefs).and("creationDate").gte(month.from()).lt(month.to());
    return count(created)
        - count(
            where("workflowRef")
                .in(workflowRefs)
                .and("creationDate")
                .gte(month.from())
                .lt(month.to())
                .and("statusReason")
                .is(StatusReason.SUBMIT_REFUSED));
  }

  // The runs waiting for a slot of the workspace: parked with the workspace's limit as their
  // reason, never the ones their own workflow's limit holds. With withUnadmitted, also the runs
  // not yet admitted or parked. The status narrows on workflow_ref_status; the reason is read
  // off the parked runs' documents.
  private static Criteria queuedForWorkspace(List<String> workflowRefs, boolean withUnadmitted) {
    Criteria parked =
        where("status")
            .is(RunStatus.waiting.name())
            .and("statusReason")
            .is(StatusReason.WORKSPACE_CONCURRENCY_LIMIT);
    return new Criteria()
        .andOperator(
            where("workflowRef").in(workflowRefs),
            withUnadmitted
                ? new Criteria()
                    .orOperator(parked, where("status").is(RunStatus.notstarted.name()))
                : parked);
  }

  private static Criteria inStatuses(List<String> workflowRefs, Set<RunStatus> statuses) {
    return where("workflowRef")
        .in(workflowRefs)
        .and("status")
        .in(statuses.stream().map(Enum::name).toList());
  }

  private long count(Criteria criteria) {
    return mongoTemplate.count(Query.query(criteria), WorkflowRunEntity.class);
  }

  /*
   * Refuses a workspace on a run submission whose requested size is over the workspace storage
   * quota. The size is a Kubernetes quantity ("1Gi", "500Mi") compared in Gi - the same rule
   * WorkflowService.applyWorkspaceDefaults applies at save. A workspace naming no size is passed
   * on blank and takes the dispatcher's own default size, which this check does not see.
   */
  private static void assertWorkspaceStorage(WorkflowWorkspace workspace, Integer maxSizeGi) {
    String size = workspace.getSize();
    if (Quotas.limited(maxSizeGi)
        && size != null
        && !size.isBlank()
        && StorageQuantityUtil.toGi(size) > maxSizeGi) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED, WORKSPACE_SIZE, size, maxSizeGi);
    }
  }

  // ── Counts ────────────────────────────────────────────────────────────────

  // The UTC calendar month a day falls in, as the half-open window every monthly figure reads:
  // from its first instant up to, not including, the first instant of the next month.
  record MonthWindow(Date from, Date to) {
    static MonthWindow of(LocalDate day) {
      LocalDate monthStart = day.withDayOfMonth(1);
      return new MonthWindow(
          Date.from(monthStart.atStartOfDay(ZoneOffset.UTC).toInstant()),
          Date.from(monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
  }

  // The figure of each of the given workflows, added up.
  private static long sum(Map<String, Long> byWorkflow, List<String> workflowRefs) {
    return workflowRefs.stream().mapToLong(ref -> byWorkflow.getOrDefault(ref, 0L)).sum();
  }

  /**
   * Runs of the given Workflows created in the window and in the given statuses, counted per
   * Workflow; a Workflow with none is absent.
   */
  private Map<String, Long> countRuns(
      List<String> workflowRefs,
      Optional<Date> from,
      Optional<Date> to,
      Optional<Set<RunStatus>> statuses) {
    if (workflowRefs.isEmpty()) {
      return Map.of();
    }
    return countByWorkflow(
        WorkflowRunEntity.class,
        new QueryCriteria()
            .in("workflowRef", workflowRefs)
            .between("creationDate", from, to)
            .in("status", statuses.map(s -> s.stream().map(Enum::name).toList()))
            .build());
  }

  // The entries refused submits left in the month, per Workflow.
  private Map<String, Long> countRefusedSubmits(List<String> workflowRefs, MonthWindow month) {
    if (workflowRefs.isEmpty()) {
      return Map.of();
    }
    return countByWorkflow(
        WorkflowRunEntity.class,
        where("workflowRef")
            .in(workflowRefs)
            .and("creationDate")
            .gte(month.from())
            .lt(month.to())
            .and("statusReason")
            .is(StatusReason.SUBMIT_REFUSED));
  }

  private Map<String, Long> countFinallyStage(List<String> workflowRefs) {
    if (workflowRefs.isEmpty()) {
      return Map.of();
    }
    return countByWorkflow(
        WorkflowRunEntity.class,
        where("workflowRef")
            .in(workflowRefs)
            .and("phase")
            .is(RunPhase.running.name())
            .and("graphEndedAt")
            .exists(true));
  }

  // The documents matching, counted per workflowRef in one aggregation.
  private Map<String, Long> countByWorkflow(Class<?> collection, Criteria criteria) {
    Map<String, Long> counts = new HashMap<>();
    mongoTemplate
        .aggregate(
            newAggregation(match(criteria), group("workflowRef").count().as("count")),
            collection,
            Document.class)
        .forEach(
            workflow ->
                counts.put(
                    workflow.getString("_id"), ((Number) workflow.get("count")).longValue()));
    return counts;
  }

  /** The active and inactive Workflows among the given refs; deleted ones no longer count. */
  private Set<String> liveWorkflows(List<String> workflowRefs) {
    if (workflowRefs.isEmpty()) {
      return Set.of();
    }
    Query query =
        new Query(
            new QueryCriteria()
                .in("id", workflowRefs)
                .in("status", List.of(WorkflowStatus.active, WorkflowStatus.inactive))
                .build());
    query.fields().include("id");
    return mongoTemplate.find(query, WorkflowEntity.class).stream()
        .map(WorkflowEntity::getId)
        .collect(Collectors.toSet());
  }

  /**
   * Bytes held by each of the given workflows' available and uploading artifacts, task logs aside;
   * a workflow holding none is absent.
   */
  private Map<String, Long> storedBytes(List<String> workflowRefs) {
    if (workflowRefs.isEmpty()) {
      return Map.of();
    }
    Map<String, Long> bytes = new HashMap<>();
    mongoTemplate
        .aggregate(
            newAggregation(
                match(
                    where("workflowRef")
                        .in(workflowRefs)
                        .and("status")
                        .in(HOLDING_STORAGE.stream().map(Enum::name).toList())
                        .and("scope")
                        .ne(ArtifactScope.log.name())),
                group("workflowRef").sum("size").as("total")),
            ArtifactEntity.class,
            Document.class)
        .forEach(
            workflow ->
                bytes.put(
                    workflow.getString("_id"),
                    (workflow.get("total") instanceof Number total) ? total.longValue() : 0L));
    return bytes;
  }

  private String setting(String name) {
    return settingsService.getSettingConfig(QUOTAS_SETTINGS_KEY, name).getValue();
  }


  private Integer intSetting(String name) {
    return Integer.valueOf(setting(name));
  }

  private int artifactSetting(String name) {
    return Integer.parseInt(
        settingsService
            .getSettingConfig(ArtifactService.ARTIFACTS_SETTINGS_KEY, name)
            .getValue()
            .trim());
  }
}
