package io.boomerang.workflow;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.common.util.StorageQuantityUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.audit.AuditQueryService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
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
  public static final String QUOTA_MAX_WORKFLOWRUN_MONTHLY = "max.workflowrun.monthly";
  public static final String QUOTA_MAX_WORKFLOWRUN_DURATION = "max.workflowrun.duration";
  public static final String QUOTA_MAX_WORKSPACE_STORAGE = "max.workspace.storage";
  public static final String QUOTA_MAX_ARTIFACT_STORAGE = "max.artifact.storage";

  // A run counts against the concurrent limit from creation until it is terminal.
  private static final Set<RunStatus> CONCURRENT_STATUSES =
      Set.of(RunStatus.notstarted, RunStatus.ready, RunStatus.running, RunStatus.waiting);

  // Artifacts that hold storage; task logs are kept apart and not counted.
  private static final List<ArtifactStatus> HOLDING_STORAGE =
      List.of(ArtifactStatus.uploading, ArtifactStatus.available);

  private final SettingsService settingsService;
  private final WorkspaceRepository workspaceRepository;
  private final RelationshipService relationshipService;
  private final AuditQueryService auditQueryService;
  private final MongoTemplate mongoTemplate;

  public QuotaService(
      SettingsService settingsService,
      WorkspaceRepository workspaceRepository,
      RelationshipService relationshipService,
      AuditQueryService auditQueryService,
      MongoTemplate mongoTemplate) {
    this.settingsService = settingsService;
    this.workspaceRepository = workspaceRepository;
    this.relationshipService = relationshipService;
    this.auditQueryService = auditQueryService;
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
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowCount(intSetting(QUOTA_MAX_WORKFLOW_COUNT));
    quotas.setMaxWorkflowRunMonthly(intSetting(QUOTA_MAX_WORKFLOWRUN_MONTHLY));
    quotas.setMaxWorkspaceStorage(
        Integer.valueOf(setting(QUOTA_MAX_WORKSPACE_STORAGE).replace("Gi", "")));
    quotas.setMaxWorkflowRunDuration(intSetting(QUOTA_MAX_WORKFLOWRUN_DURATION));
    quotas.setMaxConcurrentRuns(intSetting(QUOTA_MAX_WORKFLOWRUN_CONCURRENT));
    quotas.setMaxArtifactStorage(
        Integer.valueOf(setting(QUOTA_MAX_ARTIFACT_STORAGE).replace("Gi", "")));
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
            .findByNameIgnoreCase(workspace)
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
    if (overrides.getMaxWorkspaceStorage() != null) {
      quotas.setMaxWorkspaceStorage(overrides.getMaxWorkspaceStorage());
    }
    if (overrides.getMaxWorkflowRunDuration() != null) {
      quotas.setMaxWorkflowRunDuration(overrides.getMaxWorkflowRunDuration());
    }
    if (overrides.getMaxConcurrentRuns() != null) {
      quotas.setMaxConcurrentRuns(overrides.getMaxConcurrentRuns());
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
            .findByNameIgnoreCase(workspace)
            .map(WorkspaceEntity::getQuotas)
            .map(Quotas::getMaxWorkflowRunDuration)
            .orElse(null);
    return (stored != null) ? stored : intSetting(QUOTA_MAX_WORKFLOWRUN_DURATION);
  }

  // ── Usage ─────────────────────────────────────────────────────────────────

  /** A workspace's limits with its current usage filled in; null when no workspace has the name. */
  public CurrentQuotas current(String workspace) {
    return workspaceRepository
        .findByNameIgnoreCase(workspace)
        .map(entity -> current(entity, workspace))
        .orElse(null);
  }

  /**
   * A workspace's limits with its current usage filled in. Monthly figures cover the current UTC
   * calendar month, and the workflows counted are the ones the caller may see in the workspace.
   */
  public CurrentQuotas current(WorkspaceEntity entity) {
    return current(entity, entity.getName());
  }

  // The usage is counted under the name the caller gave, which the lookup matched ignoring case.
  private CurrentQuotas current(WorkspaceEntity entity, String workspace) {
    CurrentQuotas quotas = new CurrentQuotas(limits(entity));
    MonthWindow month = MonthWindow.of(LocalDate.now(ZoneOffset.UTC));
    quotas.setMonthlyResetDate(month.to());

    // Milliseconds, like every run duration: this month's total and the mean per run, from the
    // audit trail so runs of since-deleted Workflows still count.
    AuditQueryService.RunTotals runTotals =
        auditQueryService.runTotals(workspace, month.from(), month.to());
    quotas.setCurrentRunTotalDuration(runTotals.duration());
    quotas.setCurrentRunAverageDuration(
        (runTotals.runs() > 0) ? runTotals.duration() / runTotals.runs() : 0L);

    List<String> workflowRefs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.empty());
    // Monthly = max(audit count, live count), deliberately: the audit write is async best-effort,
    // so a just-admitted run may not be audited yet (the live count covers that race), while a
    // deleted Workflow's runs leave the live count (the audit count covers deletion - the CREATE
    // events outlive both the run documents and the Workflow).
    long liveMonthly =
        countRuns(
            workflowRefs, Optional.of(month.from()), Optional.of(month.to()), Optional.empty());
    long auditMonthly = auditQueryService.countRunsCreated(workspace, month.from(), month.to());
    quotas.setCurrentRuns((int) Math.max(auditMonthly, liveMonthly));
    // Concurrent = admitted and not yet terminal from the live WorkflowRun collection, so queued
    // work counts against the limit - it measures what is running now, which deletion
    // legitimately reduces.
    quotas.setCurrentConcurrentRuns(
        (int)
            countRuns(
                workflowRefs,
                Optional.empty(),
                Optional.empty(),
                Optional.of(CONCURRENT_STATUSES)));
    quotas.setCurrentArtifactStorage(storedBytes(workflowRefs));
    quotas.setCurrentWorkflowCount((int) countWorkflows(workflowRefs));
    return quotas;
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
              quotas.setCurrentArtifactStorage(
                  storedBytes(
                      relationshipService.findNodeRefs(
                          RelationshipType.WORKSPACE, workspaceRef, RelationshipType.WORKFLOW)));
              return quotas;
            })
        .orElse(null);
  }

  // ── Checks ────────────────────────────────────────────────────────────────

  /** Refuses a new Workflow when the workspace is over its workflow count, when enforced. */
  public void assertCanCreateWorkflow(String workspace) {
    if (enforced()) {
      CurrentQuotas quotas = current(workspace);
      LOGGER.debug("Quotas: {}", quotas.toString());
      if (Quotas.limited(quotas.getMaxWorkflowCount())
          && quotas.getCurrentWorkflowCount() > quotas.getMaxWorkflowCount()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Number of Workflows",
            quotas.getCurrentWorkflowCount(),
            quotas.getMaxWorkflowCount());
      }
    }
  }

  /**
   * Refuses, with {@code QUOTA_EXCEEDED}, a run the workspace's quotas have no room for:
   * concurrent runs, runs this month, and the size of any Workspace it asks for. A submit and a
   * retry - a retry is a new run - both clear it before the run is created.
   */
  public void assertRunQuotas(String workspace, Optional<WorkflowWorkspace> runWorkspace) {
    if (enforced()) {
      CurrentQuotas quotas = current(workspace);
      LOGGER.debug("Quotas: {}", quotas.toString());
      // The run being submitted is not counted yet, so at-the-limit already means it would
      // exceed the limit.
      if (Quotas.limited(quotas.getMaxConcurrentRuns())
          && quotas.getCurrentConcurrentRuns() >= quotas.getMaxConcurrentRuns()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Concurrent runs (executions)",
            quotas.getCurrentConcurrentRuns(),
            quotas.getMaxConcurrentRuns());
      } else if (Quotas.limited(quotas.getMaxWorkflowRunMonthly())
          && quotas.getCurrentRuns() >= quotas.getMaxWorkflowRunMonthly()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Number of runs (executions)",
            quotas.getCurrentRuns(),
            quotas.getMaxWorkflowRunMonthly());
      } else if (runWorkspace.isPresent()) {
        assertWorkspaceStorage(runWorkspace.get(), quotas);
      }
    }
  }

  /**
   * Refuses a run once it has been admitted when it pushed a count over its limit. The counts now
   * include the run itself, so a burst of simultaneous submits cannot each pass the pre-admission
   * count; the caller removes the run before passing the refusal on.
   */
  public void assertAdmittedRunWithinQuotas(String workspace) {
    CurrentQuotas quotas = current(workspace);
    if (Quotas.limited(quotas.getMaxConcurrentRuns())
        && quotas.getCurrentConcurrentRuns() > quotas.getMaxConcurrentRuns()) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Concurrent runs (executions)",
          quotas.getCurrentConcurrentRuns(),
          quotas.getMaxConcurrentRuns());
    } else if (Quotas.limited(quotas.getMaxWorkflowRunMonthly())
        && quotas.getCurrentRuns() > quotas.getMaxWorkflowRunMonthly()) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Number of runs (executions)",
          quotas.getCurrentRuns(),
          quotas.getMaxWorkflowRunMonthly());
    }
  }

  /*
   * Refuses a workspace on a run submission whose requested size is over the workspace storage
   * quota. The size is a Kubernetes quantity ("1Gi", "500Mi") compared in Gi - the same rule
   * WorkflowService.applyWorkspaceDefaults applies at save. A workspace naming no size takes the
   * quota default and cannot breach it.
   */
  private static void assertWorkspaceStorage(WorkflowWorkspace workspace, CurrentQuotas quotas) {
    Integer maxSizeGi = quotas.getMaxWorkspaceStorage();
    String size = workspace.getSize();
    if (Quotas.limited(maxSizeGi)
        && size != null
        && !size.isBlank()
        && StorageQuantityUtil.toGi(size) > maxSizeGi) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED, "Requested Workspace size", size, maxSizeGi);
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

  /** Runs of the given Workflows created in the window and in the given statuses, counted. */
  private long countRuns(
      List<String> workflowRefs,
      Optional<Date> from,
      Optional<Date> to,
      Optional<Set<RunStatus>> statuses) {
    Query query =
        new Query(
            new QueryCriteria()
                .in("workflowRef", workflowRefs)
                .between("creationDate", from, to)
                .in("status", statuses)
                .build());
    return mongoTemplate.count(query, WorkflowRunEntity.class);
  }

  /** The active and inactive Workflows among the given refs; deleted ones no longer count. */
  private long countWorkflows(List<String> workflowRefs) {
    if (workflowRefs.isEmpty()) {
      return 0;
    }
    Query query =
        new Query(
            new QueryCriteria()
                .in("id", workflowRefs)
                .in("status", List.of(WorkflowStatus.active, WorkflowStatus.inactive))
                .build());
    return mongoTemplate.count(query, WorkflowEntity.class);
  }

  /** Bytes held by the given workflows' available and uploading artifacts, task logs aside. */
  private long storedBytes(List<String> workflowRefs) {
    if (workflowRefs.isEmpty()) {
      return 0;
    }
    Document total =
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
                    group().sum("size").as("total")),
                ArtifactEntity.class,
                Document.class)
            .getUniqueMappedResult();
    return total == null ? 0 : ((Number) total.get("total")).longValue();
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
