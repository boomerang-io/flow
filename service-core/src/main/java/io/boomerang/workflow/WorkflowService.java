package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.boomerang.common.util.PagingUtil;
import io.boomerang.common.util.QueryCriteria;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.*;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.common.util.StorageQuantityUtil;
import io.boomerang.common.util.StringUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.audit.SecretChangeHolder;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.TaskExecutionService;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.schedule.ScheduleService;
import io.boomerang.workflow.ConvertUtil;
import io.boomerang.workflow.model.CanvasEdge;
import io.boomerang.workflow.model.CanvasEdgeData;
import io.boomerang.workflow.model.CanvasNode;
import io.boomerang.workflow.model.CanvasNodeData;
import io.boomerang.workflow.model.CanvasNodePosition;
import io.boomerang.workflow.model.WorkflowCanvas;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The Workflow domain service - one service for every Workflow operation the product performs.
 *
 * <p>It has two entry shapes over the same operations:
 *
 * <ul>
 *   <li><b>Workspace-scoped</b> ({@code get(team, name, ...)}, {@code submit(team, name, ...)}, ...)
 *       - the {@code /api/v2/workspace/&#123;workspace&#125;/workflow} surface. Each one resolves
 *       the Workflow slug through {@link RelationshipService#filter} within the named workspace
 *       before doing the work, converts TaskRefs to slugs for the UI, filters password-typed param
 *       values and strips ids.
 *   <li><b>Unscoped</b> ({@code get(workflowId, ...)}, {@code submit(workflowId, ...)}, ...) -
 *       internal callers that carry no workspace and are authorized elsewhere or not at all: the
 *       engine's {@code TaskExecutionService} running a {@code runworkflow} task and {@link
 *       ActionService} resolving an Action's Workflow name.
 * </ul>
 *
 * <p>The former {@code api.WorkspaceWorkflowService} pass-through was collapsed into this class:
 * the workspace resolution and the operation live together instead of being split across a service
 * boundary that only existed because the engine used to be a separate deployable.
 *
 * <p>workspace.WorkspaceService and schedule.ScheduleService are held as ObjectProvider, not as
 * fields: both take this service back in their own constructors, and Optional would resolve eagerly
 * and close the cycle at construction time.
 *
 * <p>TODO: migrate Triggers to an alternative workflow_triggers collection and use Relationships to
 * adjust.
 */
@Service
public class WorkflowService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final String CHANGELOG_INITIAL = "Initial Workflow";
  private static final String CHANGELOG_UPDATE = "Updated Workflow";
  private static final String ANNOTATION_GENERATION = "4";
  private static final String ANNOTATION_KIND = "Workflow";

  // The task types that can run for each item: those a dispatcher runs as claimable TaskRuns, and
  // runworkflow, whose items each submit a child run.
  private static final Set<TaskType> FOREACH_TASK_TYPES =
      EnumSet.of(
          TaskType.template,
          TaskType.script,
          TaskType.custom,
          TaskType.ai,
          TaskType.generic,
          TaskType.uploadartifact,
          TaskType.downloadartifact,
          TaskType.runworkflow);
  // One whole-value reference such as $(tasks.stage.results.batches) or $(params.repos).
  private static final Pattern FOREACH_ITEM_NAME_PATTERN = Pattern.compile("^.*\\[\\d+\\]$");
  private static final Pattern FOREACH_REFERENCE_PATTERN = Pattern.compile("^\\$\\([^()\\s]+\\)$");
  public static final String FEATURES_SETTINGS_KEY = "features";
  public static final String FEATURES_WORKSPACE_QUOTA = "workspaceQuotas";
  public static final String QUOTA_MAX_WORKFLOW_DURATION = "max.workflow.duration";
  public static final String TASK_SETTINGS_KEY = "task";

  private final WorkflowRepository workflowRepository;
  private final WorkflowRevisionRepository workflowRevisionRepository;
  private final TaskRevisionRepository taskRevisionRepository;
  private final MongoTemplate mongoTemplate;
  private final TaskService taskService;
  private final WorkflowRunService workflowRunService;
  private final RelationshipService relationshipService;
  private final ObjectProvider<ScheduleService> scheduleService;
  private final ParamLayerService paramLayerService;
  private final SettingsService settingsService;
  private final ActionRepository actionRepository;
  private final TokenService tokenService;
  private final ObjectProvider<WorkspaceService> workspaceService;
  private final RunTimeoutPolicy runTimeoutPolicy;
  private final ObjectMapper objectMapper;
  private final ParamLayerCache paramLayerCache;

  // json-path (Configuration/JacksonMappingProvider/JacksonJsonNodeJsonProvider) only ships
  // Jackson 2 SPIs - com.fasterxml.jackson.databind.ObjectMapper, not the Boot-managed Jackson 3
  // bean - so validateTriggerConditions() needs its own Jackson 2 mapper.
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON_PATH_MAPPER =
      com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

  public WorkflowService(
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      TaskRevisionRepository taskRevisionRepository,
      MongoTemplate mongoTemplate,
      TaskService taskService,
      WorkflowRunService workflowRunService,
      RelationshipService relationshipService,
      ObjectProvider<ScheduleService> scheduleService,
      ParamLayerService paramLayerService,
      SettingsService settingsService,
      ActionRepository actionRepository,
      TokenService tokenService,
      ObjectProvider<WorkspaceService> workspaceService,
      RunTimeoutPolicy runTimeoutPolicy,
      ObjectMapper objectMapper,
      ParamLayerCache paramLayerCache) {
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.taskRevisionRepository = taskRevisionRepository;
    this.mongoTemplate = mongoTemplate;
    this.taskService = taskService;
    this.workflowRunService = workflowRunService;
    this.relationshipService = relationshipService;
    this.scheduleService = scheduleService;
    this.paramLayerService = paramLayerService;
    this.settingsService = settingsService;
    this.actionRepository = actionRepository;
    this.tokenService = tokenService;
    this.workspaceService = workspaceService;
    this.runTimeoutPolicy = runTimeoutPolicy;
    this.objectMapper = objectMapper;
    this.paramLayerCache = paramLayerCache;
  }

  // ── Workspace-scoped operations (the /api/v2 surface) ────────────────────────
  //
  // Every method here performs the SAME relationship call the deleted
  // api.WorkspaceWorkflowService performed, then does the work inline.

  /*
   * Whether quota limits are enforced: the stored "workspaceQuotas" feature setting is the one
   * switch.
   */
  boolean quotasEnforced() {
    return settingsService
        .getSettingConfig(FEATURES_SETTINGS_KEY, FEATURES_WORKSPACE_QUOTA)
        .getBooleanValue();
  }

  /*
   * The workspace that owns a Workflow, or null when the relationship graph records none. The
   * run-duration ceiling is a property of the owner, not of the path a caller arrived on, so the
   * chokepoint submit resolves it here rather than trusting a team segment - and callers that
   * carry no team at all (the engine running a runworkflow task) get the same ceiling as everyone
   * else.
   */
  private String owningWorkspaceOrNull(String workflowId) {
    String ref =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowId);
    if (ref == null || ref.isBlank()) {
      return null;
    }
    // The edge records the workspace's ref; the quota lookup is by name.
    try {
      return relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, ref);
    } catch (RuntimeException e) {
      LOGGER.warn("[{}] Owning workspace {} has no relationship node.", workflowId, ref);
      return null;
    }
  }

  /*
   * The ceiling for a WorkflowRun's timeout, in minutes: the workspace's max run duration (its own
   * quota if stored, else the platform default in the "quotas" settings document).
   *
   * 0 is no ceiling: a workspace whose stored quota is 0 (unlimited), or a Workflow no workspace
   * owns - only the floor and the platform default constrain those runs.
   */
  public long maxWorkflowDuration(String team) {
    if (team == null) {
      return 0;
    }
    return workspaceService.getObject().getWorkflowMaxDurationForTeam(team).longValue();
  }

  /*
   * Get Worklfow
   *
   * No need to validate params as they are either defaulted or optional
   */
  public Workflow get(String team, String name, Optional<Integer> version, boolean withTasks) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    Workflow workflow = get(workflowRef(team, name), version, withTasks).getBody();

    // Filter out sensitive values - before the slug conversion, while the task refs still resolve
    filterSensitiveValues(List.of(workflow));

    // Convert Workflow TaskRefs to Slugs
    convertTaskRefsToSlugs(team, workflow);

    workflow.setId(null);
    return workflow;
  }

  /*
   * The unfiltered read, for duplicate only: a copy must carry the stored secrets, not the marker.
   */
  private Workflow internalGet(
      String team, String name, Optional<Integer> version, boolean withTasks) {
    Workflow workflow = get(workflowRef(team, name), version, withTasks).getBody();

    // Convert Workflow TaskRefs to Slugs
    convertTaskRefsToSlugs(team, workflow);
    return workflow;
  }

  private String workflowRef(String team, String name) {
    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, team, Optional.of(List.of(name)));
    if (refs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    return refs.get(0);
  }

  /*
   * Every read of a Workflow through the /api/v2 surface - get, query, export, and the create,
   * apply and duplicate responses - hides its secrets with the one read shape
   * (DataAdapterUtil.maskSecretValue): a password-typed workflow param's default, and a task
   * node's value for a param its task declares as password, read as the redaction marker when set
   * and as nothing when unset; one that is exactly one Flow reference reads as written. The node's
   * type is stamped from the task's spec here as well as at save, so a revision saved before
   * stamping existed is hidden too; the specs are resolved in one batch for the whole page. The
   * canvas reads (composeGet, composeApply) are filtered the same way. Must run while the task refs
   * are still refs, before convertTaskRefsToSlugs.
   */
  private void filterSensitiveValues(List<Workflow> workflows) {
    Set<TaskService.TaskRef> refs =
        workflows.stream()
            .filter(w -> w.getTasks() != null)
            .flatMap(w -> w.getTasks().stream())
            .filter(t -> t.getTaskRef() != null && t.getTaskVersion() != null)
            .map(t -> new TaskService.TaskRef(t.getTaskRef(), t.getTaskVersion()))
            .collect(Collectors.toSet());
    Map<TaskService.TaskRef, TaskSpec> specs = taskService.getSpecs(refs);
    for (Workflow workflow : workflows) {
      DataAdapterUtil.filterParamSpecValueByFieldType(
          workflow.getParams(), FieldType.PASSWORD.value());
      if (workflow.getTasks() == null) {
        continue;
      }
      for (WorkflowTask task : workflow.getTasks()) {
        TaskSpec spec = specs.get(new TaskService.TaskRef(task.getTaskRef(), task.getTaskVersion()));
        if (spec != null) {
          ParameterUtil.stampSecretParams(task.getParams(), spec.getParams());
        }
        task.setParams(
            DataAdapterUtil.filterDefinitionValueByFieldType(task.getParams(), ParamType.secret));
      }
    }
  }

  /*
   * Query for Workflows.
   */
  public WorkflowResponsePage query(
      String queryTeam,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryWorkflows) {

    // Get Refs that request has access to
    List<String> refs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, queryTeam, queryWorkflows);
    LOGGER.debug("Workflow Refs: {}", refs.toString());
    if (refs.isEmpty()) {
      return new WorkflowResponsePage();
    }

    Page<Workflow> page =
        query(
            queryLimit, queryPage, querySort, queryLabels, queryStatus, Optional.of(refs));
    WorkflowResponsePage response =
        new WorkflowResponsePage(page.getContent(), page.getPageable(), page.getTotalElements());

    LOGGER.debug("Workflow Response: {} workflows", response.getContent().size());
    if (!response.getContent().isEmpty()) {
      // Filter out sensitive values
      filterSensitiveValues(response.getContent());
      response
          .getContent()
          .forEach(
              w -> {
                // Convert Workflow TaskRefs to Slugs
                convertTaskRefsToSlugs(queryTeam, w);
                w.setId(null);
              });
    }

    return response;
  }

  /*
   * Retrieve the statistics for a specific period of time and filters
   */
  public WorkflowCount count(
      String queryTeam,
      Optional<Long> from,
      Optional<Long> to,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows) {
    // Get Refs that request has access to
    List<String> refs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, queryTeam, queryWorkflows);
    LOGGER.debug("Workflow Refs: {}", refs.toString());

    // Handle no Workflows for Workspace. Otherwise the engine will return all workflows due to no filter
    if (refs.size() > 0) {
      return count(from.map(Date::new), to.map(Date::new), queryLabels, Optional.of(refs))
          .getBody();
    }
    return new WorkflowCount();
  }

  /*
   * Create Workflow. Pass query onto the workflow WorkflowService
   *
   * No need to validate params as they are either defaulted or optional
   */
  //  @Audit(scope = PermissionScope.WORKFLOW)
  public Workflow create(String team, Workflow request) {
    // Validate Access - same guard as TaskService.create: the caller must reach the workspace the
    // ownership edge below is written into. Guards every create-shaped route: create, apply and
    // composeApply (when the Workflow does not exist yet) and duplicate all funnel here.
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }

    // Ensure name is in slug format
    if (request.getName() != null && !request.getName().isBlank()) {
      request.setName(StringUtil.kebabCase(request.getName()));
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    // Fill in displayName if not set
    validateAndSetDisplayName(request);
    LOGGER.debug("Workflow DisplayName: {}", request.getDisplayName());

    // Ensure Workflow name is unique within Workspace
    List<String> existingWorkflowRefs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, team, Optional.of(List.of(request.getName())));
    if (!existingWorkflowRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    // Check creation quotas
    canCreateWithQuotas(team);

    // Default Triggers
    validateTriggerDefaults(request);

    // Default Workspace
    applyWorkspaceDefaults(request);

    // Convert TaskRefs to IDs
    convertTaskSlugsToRefs(team, request);

    request.setId(null);

    Workflow workflow = create(request, false).getBody();
    LOGGER.debug("Workflow DisplayName: {}", workflow.getDisplayName());

    // Create Relationship
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        team,
        RelationshipLabel.HAS_WORKFLOW,
        RelationshipType.WORKFLOW,
        workflow.getId(),
        workflow.getName(),
        Optional.empty(),
        Optional.empty());

    // TODO go through and ensure all the required ParamSpec elements are set

    // Filter out sensitive values
    filterSensitiveValues(List.of(workflow));

    // Convert Workflow TaskRefs to Slugs
    convertTaskRefsToSlugs(team, workflow);

    // Remove ID from Workflow
    workflow.setId(null);
    return workflow;
  }

  private static void validateAndSetDisplayName(Workflow request) {
    if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
      request.setDisplayName(request.getName());
    }
  }

  /*
   * Settles the Workflow's workspace on every save (create and update alike): a blank size takes
   * the storage quota, a blank mountPath takes the default, and - only when quotas are enforced -
   * an authored size over quota is refused. The size is then written as a Kubernetes quantity
   * ("1Gi", "500Mi"): a bare number is the Gi shorthand authors use, and the dispatcher passes the
   * value straight to a Kubernetes Quantity, which would read a bare number as bytes. A Workflow
   * without a workspace has no shared storage and nothing to settle.
   */
  private void applyWorkspaceDefaults(Workflow request) {
    WorkflowWorkspace workspace = request.getWorkspace();
    if (workspace == null) {
      return;
    }
    String maxStorageSizeQuota =
        this.settingsService
            .getSettingConfig(
                WorkspaceService.QUOTAS_SETTINGS_KEY, WorkspaceService.QUOTA_MAX_WORKSPACE_STORAGE)
            .getValue();
    if (workspace.getSize() == null || workspace.getSize().isBlank()) {
      workspace.setSize(maxStorageSizeQuota);
    } else if (quotasEnforced()
        && StorageQuantityUtil.toGi(workspace.getSize())
            > StorageQuantityUtil.toGi(maxStorageSizeQuota)) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Workspace Size Limit",
          workspace.getSize(),
          maxStorageSizeQuota);
    }
    workspace.settle();
  }

  /*
   * Apply allows you to create a new version or override an existing Workflow as well as create new
   * Workflow with supplied ID
   */
  public Workflow apply(String team, Workflow workflow, boolean replace) {
    if (workflow != null && workflow.getName() != null && !workflow.getName().isBlank()) {
      List<String> refs =
          relationshipService.refsInWorkspace(
              RelationshipType.WORKFLOW, team, Optional.of(List.of(workflow.getName())));
      if (!refs.isEmpty()) {
        workflow.setId(refs.get(0));

        // Fill in displayName if not set
        validateAndSetDisplayName(workflow);

        // Default Workspace - the same rule create applies, so an update cannot slip past it
        applyWorkspaceDefaults(workflow);

        // A trigger the request leaves out keeps its stored value, as labels do; then sync the
        // workflow's schedules with the schedule trigger.
        WorkflowTrigger currentTriggers =
            this.get(team, workflow.getName(), Optional.empty(), false).getTriggers();
        workflow.setTriggers(withStoredTriggers(workflow.getTriggers(), currentTriggers));
        updateScheduleTriggers(team, workflow, currentTriggers);

        // Convert TaskSlugs to Refs(IDs)
        convertTaskSlugsToRefs(team, workflow);

        // TODO go through and ensure all the required ParamSpec elements are set

        Workflow appliedWorkflow = apply(workflow, replace).getBody();

        // Filter out sensitive values
        filterSensitiveValues(List.of(appliedWorkflow));

        // Convert Workflow TaskRefs(IDs) to Slugs
        convertTaskRefsToSlugs(team, appliedWorkflow);

        workflow.setId(null);
        return appliedWorkflow;
      }
    }
    if (workflow != null) {
      workflow.setId(null);
      return this.create(team, workflow);
    }
    throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
  }

  /*
   * Submit Workflow to Run
   */
  public WorkflowRun submit(
      String team, String name, WorkflowSubmitRequest request, boolean start) {
    return submit(team, name, request, start, null);
  }

  /*
   * Submit Workflow to Run, internally stamping lineage
   *
   * initiatedByRef is NOT sourced from the request - WorkflowSubmitRequest is a public API model
   * and lineage must not be spoofable by API callers. Callers that need it (e.g. ScheduleJob,
   * with the firing Schedule's id) pass it directly; every other caller goes through the 4-arg
   * overload above and gets null, matching prior behaviour.
   */
  public WorkflowRun submit(
      String team, String name, WorkflowSubmitRequest request, boolean start,
      String initiatedByRef) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, team, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      WorkflowRun wfRun = this.internalSubmit(team, refs.get(0), request, start, initiatedByRef);
      // The submit response is a consumer read like any other: secret-typed params go out
      // redacted. The filter copies the params, so the run the engine holds is untouched.
      DataAdapterUtil.filterWorkflowRunValueByFieldType(wfRun, ParamType.secret);
      return wfRun;
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
  }

  /*
   * Submit WorkflowRun Internally by Workspace
   *
   * Used by TriggerService
   *
   * TODO: surely there is a better way to do this
   */
  public void internalSubmitForTeam(String team, WorkflowSubmitRequest request, boolean start) {
    // This should return IDs as the next method requires to take in the Workflow ID
    List<String> wfRefs =
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, team, Optional.empty());
    wfRefs.forEach(
        r -> {
          this.internalSubmit(team, r, request, start);
        });
  }

  /*
   * Submit WorkflowRun Internally
   *
   * Caution: bypasses the authN and authZ and Relationship checks
   */
  public WorkflowRun internalSubmit(
      String team, String workflowId, WorkflowSubmitRequest request, boolean start) {
    return internalSubmit(team, workflowId, request, start, null);
  }

  /*
   * Submit WorkflowRun Internally, internally stamping lineage - see submit(..., initiatedByRef)
   *
   * Caution: bypasses the authN and authZ and Relationship checks
   */
  public WorkflowRun internalSubmit(
      String team, String workflowId, WorkflowSubmitRequest request, boolean start,
      String initiatedByRef) {
    // Check if Workflow exists and is active. Then check triggers are enabled.
    // Presumed workflow exists as relationship was valid to get to this point.
    Workflow workflow = get(workflowId, Optional.empty(), false).getBody();
    // Check Triggers - Throws Exception - Check first, as if trigger not enabled, no point in
    // checking quotas
    canRunWithTrigger(workflow.getTriggers(), request.getTrigger(), request.getParams());
    // Check Quotas - Throws Exception
    assertRunQuotas(team, Optional.ofNullable(request.getWorkspace()));
    // Set Workflow & Task Debug
    if (Objects.isNull(request.getDebug())) {
      boolean enableDebug = false;
      String setting = this.settingsService.getSettingConfig("task", "debug").getValue();
      if (setting != null) {
        enableDebug = Boolean.parseBoolean(setting);
      }
      request.setDebug(Boolean.valueOf(enableDebug));
      LOGGER.info("Setting debug = " + enableDebug);
    }
    // These annotations are processed by the DAGUtility in the Engine
    Map<String, Object> executionAnnotations = new HashMap<>();
    executionAnnotations.put(
        "boomerang.io/task-deletion",
        this.settingsService.getSettingConfig(TASK_SETTINGS_KEY, "deletion.policy").getValue());
    executionAnnotations.put(
        "boomerang.io/task-default-image",
        this.settingsService.getSettingConfig(TASK_SETTINGS_KEY, "default.image").getValue());
    // The per-task ceiling is an optional operator control, not a budget every task is entitled
    // to spend: the seed ships it at 0 so a task inherits its run's timeout, and an operator who
    // wants a hard per-pod limit sets a number. A blank value means the same as 0 - an install
    // whose setting was cleared by hand or arrived blank from a v3 migration stamps no annotation
    // at all rather than handing DAGUtility a string Long.parseLong cannot read.
    String taskTimeoutSetting =
        this.settingsService.getSettingConfig(TASK_SETTINGS_KEY, "default.timeout").getValue();
    if (taskTimeoutSetting != null && !taskTimeoutSetting.isBlank()) {
      executionAnnotations.put("boomerang.io/task-timeout", taskTimeoutSetting);
    }

    // Global, workspace and context parameters are not copied onto the run: the engine reads them
    // from their stores each time it resolves. A child run's request carries its parent's
    // annotations, so the keys older runs were given are dropped here too.
    request.getAnnotations().keySet().removeAll(ParamLayerService.LEGACY_RUN_ANNOTATIONS);

    // Add Contextual Information such as team-name. Used by Engine and the AcquireTaskLock and
    // other tasks to add a hidden prefix.
    executionAnnotations.put("boomerang.io/workspace-name", team);
    request.getAnnotations().putAll(executionAnnotations);

    // Admit-then-recheck: with quotas enforced the run is created un-started, the quota counts
    // are re-read (now including this run), and a run that pushed a count over its limit is
    // discarded - simultaneous submits cannot each slip past the pre-admission count above.
    boolean enforceQuotas = quotasEnforced();
    WorkflowRun wfRun = submit(workflowId, request, start && !enforceQuotas, initiatedByRef);
    if (enforceQuotas) {
      guardQuotasAfterAdmission(team, wfRun.getId());
    }

    // Creates relationship with owning team
    // TODO: create this run relationship based on decision of team vs workflow
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        team,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        wfRun.getId(),
        wfRun.getId(),
        Optional.empty(),
        Optional.empty());
    // A run with a workspace stays ready/pending for the dispatcher to provision and start -
    // the same rule WorkflowRunService.run applies on the un-enforced path.
    if (enforceQuotas && start && wfRun.getWorkspace() == null) {
      wfRun = workflowRunService.start(wfRun.getId(), Optional.empty());
    }
    return wfRun;
  }

  /*
   * Retrieve a workflows changelog from all versions
   */
  public ResponseEntity<List<ChangeLogVersion>> changelog(String team, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, team, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      return ResponseEntity.ok(changelog(refs.get(0)).getBody());
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
  }

  /*
   * Scoped delete: tombstones the Workflow, then cleans up what only this layer knows about -
   * Schedules, the principal's Tokens, open Actions, and the relationship node. The documents
   * themselves (Workflow, WorkflowRuns, TaskRuns, revisions) are hard-deleted by the watcher's
   * prune sweep once in-flight runs finalise.
   */
  public void delete(String team, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, team, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      // Tombstones the Workflow; the watcher winds down its runs and prunes the documents.
      delete(refs.get(0));
      scheduleService.getObject().deleteAllForWorkflow(refs.get(0));
      tokenService.deleteAllForPrincipal(name);
      // Close out the Workflow's Actions. Inlined from the deleted
      // ActionService.deleteAllByWorkflow, which was a one-line delegation to this repository
      // call and had no other caller - injecting ActionService here would make
      // WorkflowService -> ActionService -> WorkflowService a constructor cycle.
      actionRepository.deleteByWorkflowRef(refs.get(0));
      // This has to be the ID (ref) as it is unique across all teams
      relationshipService.removeNodeAndEdgeByRef(RelationshipType.WORKFLOW, refs.get(0));
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
  }

  /*
   * Export the Workflow as JSON
   */
  public ResponseEntity<InputStreamResource> export(String team, String name) {
    final Workflow workflow = this.get(team, name, Optional.empty(), true);

    HttpHeaders headers = new HttpHeaders();
    headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
    headers.add("Pragma", "no-cache");
    headers.add("Expires", "0");
    headers.add("Content-Disposition", "attachment; filename=\"any_name.json\"");

    try {

      byte[] buf = objectMapper.writeValueAsBytes(workflow);

      return ResponseEntity.ok()
          .contentLength(buf.length)
          .contentType(MediaType.parseMediaType("application/octet-stream"))
          .body(new InputStreamResource(new ByteArrayInputStream(buf)));
    } catch (JacksonException e) {

      LOGGER.error(e);
    }
    return null;
  }

  /*
   * Duplicate the Workflow and adjust name
   *
   * Relationship checks are handled in the Get and Create methods
   */
  public Workflow duplicate(String team, String name) {
    // From the unfiltered read: a copy of the filtered one would carry the redaction marker in
    // place of every secret, and the duplicate would lose them.
    Workflow workflow = this.internalGet(team, name, Optional.empty(), true);
    workflow.setName(workflow.getName() + "-duplicate");
    workflow.setDisplayName(workflow.getDisplayName() + " (duplicate)");
    return this.create(team, workflow);
  }

  /*
   * Retrieves Workflow with Tasks and converts / composes it to the appropriate model.
   *
   * Relationship check handled in Get
   *
   * TODO: add a type to handle canvas or Tekton YAML etc etc
   */
  public WorkflowCanvas composeGet(String team, String name, Optional<Integer> version) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    // The same masked read as get, keeping the id the canvas carries.
    Workflow response = get(workflowRef(team, name), version, true).getBody();
    filterSensitiveValues(List.of(response));
    convertTaskRefsToSlugs(team, response);
    return convertWorkflowToCanvas(response);
  }

  /*
   * Retrieves Workflow with Tasks and converts / composes it to the appropriate model.
   *
   * Relationship check handled in Apply
   *
   * TODO: add a type to handle canvas or Tekton YAML etc etc
   */
  public WorkflowCanvas composeApply(String team, WorkflowCanvas canvas, boolean replace) {
    if (canvas == null) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    Workflow workflow = convertCanvasToWorkflow(canvas);
    Workflow response = this.apply(team, workflow, replace);
    return convertWorkflowToCanvas(response);
  }

  /*
   * Forms the param layers (keys only)
   *
   * Used by the UI to provide helpful prompts on available params
   *
   * Relationship check handled in Get
   */
  public List<String> getAvailableParameters(String team, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    final Workflow workflow = this.get(team, name, Optional.empty(), true);
    List<String> paramKeys = paramLayerService.buildParamKeys(team, workflow);
    workflow
        .getTasks()
        .forEach(
            t -> {
              if (t.getResults() != null && !t.getResults().isEmpty()) {
                t.getResults()
                    .forEach(
                        r -> {
                          String key = "tasks." + t.getName() + ".results." + r.getName();
                          paramKeys.add(key);
                        });
              }
            });
    return paramKeys;
  }

  /*
   * Sets up the Triggers
   */
  private void validateTriggerDefaults(Workflow workflow) {
    workflow.setTriggers(ConvertUtil.triggersWithDefaults(workflow.getTriggers()));
  }

  /*
   * The requested triggers, with each one the request left out taken from the stored workflow.
   */
  private static WorkflowTrigger withStoredTriggers(
      WorkflowTrigger requested, WorkflowTrigger stored) {
    if (requested == null) {
      return stored;
    }
    WorkflowTrigger merged = new WorkflowTrigger();
    merged.setManual(Objects.requireNonNullElse(requested.getManual(), stored.getManual()));
    merged.setSchedule(Objects.requireNonNullElse(requested.getSchedule(), stored.getSchedule()));
    merged.setWebhook(Objects.requireNonNullElse(requested.getWebhook(), stored.getWebhook()));
    merged.setEvent(Objects.requireNonNullElse(requested.getEvent(), stored.getEvent()));
    merged.setGithub(Objects.requireNonNullElse(requested.getGithub(), stored.getGithub()));
    return merged;
  }

  /*
   * Determine if Schedules need to be disabled based on triggers
   */
  private void updateScheduleTriggers(
      final String team, final Workflow request, WorkflowTrigger currentTriggers) {
    ScheduleService schedules = scheduleService.getObject();
    if (!Objects.isNull(request.getTriggers())
        && !Objects.isNull(request.getTriggers().getSchedule())
        && !Objects.isNull(currentTriggers)
        && !Objects.isNull(currentTriggers.getSchedule())) {
      boolean currentSchedulerEnabled = currentTriggers.getSchedule().getEnabled();
      boolean requestSchedulerEnabled = request.getTriggers().getSchedule().getEnabled();
      if (currentSchedulerEnabled != false && requestSchedulerEnabled == false) {
        schedules.disableAllTriggerSchedules(team, request.getId());
      } else if (currentSchedulerEnabled == false && requestSchedulerEnabled == true) {
        schedules.enableAllTriggerSchedules(team, request.getId());
      }
    }
  }

  /*
   * Check if the Workspace Quotas allow a Workflow to be created. A quota of 0 is unlimited and
   * is not checked (Quotas.limited), here and in every run check below.
   */
  private void canCreateWithQuotas(String team) {
    if (quotasEnforced()) {
      CurrentQuotas quotas = workspaceService.getObject().getCurrentQuotas(team);
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
   * concurrent runs, runs this month, and the size of any Workspace it asks for. Public because a
   * retry is a new run and has to clear the same limits a submit does - WorkflowRunService.retry
   * calls this before it saves the clone.
   */
  public void assertRunQuotas(String team, Optional<WorkflowWorkspace> workspace) {
    if (quotasEnforced()) {
      CurrentQuotas quotas = workspaceService.getObject().getCurrentQuotas(team);
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
      } else if (workspace.isPresent()) {
        checkWorkspaceStorageQuota(workspace.get(), quotas);
      }
    }
  }

  /*
   * Refuses a workspace on a run submission whose requested size is over the workspace storage
   * quota. The size is a Kubernetes quantity ("1Gi", "500Mi") compared in Gi - the same rule
   * applyWorkspaceDefaults applies at save. A workspace naming no size takes the quota default
   * and cannot breach it.
   */
  private void checkWorkspaceStorageQuota(WorkflowWorkspace workspace, CurrentQuotas quotas) {
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

  /*
   * Re-checks the run quotas after a WorkflowRun has been admitted. The counts now include the
   * run itself, so a burst of simultaneous submits cannot each pass the pre-admission count:
   * every run that pushed a count over its limit removes itself and its submit fails.
   */
  private void guardQuotasAfterAdmission(String team, String workflowRunId) {
    CurrentQuotas quotas = workspaceService.getObject().getCurrentQuotas(team);
    if (Quotas.limited(quotas.getMaxConcurrentRuns())
        && quotas.getCurrentConcurrentRuns() > quotas.getMaxConcurrentRuns()) {
      discardRun(workflowRunId);
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Concurrent runs (executions)",
          quotas.getCurrentConcurrentRuns(),
          quotas.getMaxConcurrentRuns());
    } else if (Quotas.limited(quotas.getMaxWorkflowRunMonthly())
        && quotas.getCurrentRuns() > quotas.getMaxWorkflowRunMonthly()) {
      discardRun(workflowRunId);
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED,
          "Number of runs (executions)",
          quotas.getCurrentRuns(),
          quotas.getMaxWorkflowRunMonthly());
    }
  }

  /*
   * Removes a quota-rejected WorkflowRun and its materialised TaskRuns. The run was never
   * started and no relationship edge exists yet, so removing the documents is the whole undo -
   * and keeps a rejected submit from consuming quota itself.
   */
  private void discardRun(String workflowRunId) {
    mongoTemplate.remove(
        Query.query(Criteria.where("workflowRunRef").is(workflowRunId)), TaskRunEntity.class);
    mongoTemplate.remove(
        Query.query(Criteria.where("_id").is(workflowRunId)), WorkflowRunEntity.class);
  }

  /*
   * Checks if the Workflow can be executed based on an active workflow and enabled triggers.
   *
   * @param workflowId the Workflows unique ID
   *
   * @param Trigger an optional Trigger object
   */
  protected void canRunWithTrigger(
      WorkflowTrigger triggers, TriggerEnum runTrigger, List<RunParam> params) {
    // Check no further if trigger not provided
    if (!Objects.isNull(runTrigger)) {
      if (!Objects.isNull(triggers)) {
        if (TriggerEnum.manual.equals(runTrigger) && triggers.getManual().getEnabled()) {
          return;
        } else if (TriggerEnum.schedule.equals(runTrigger) && triggers.getSchedule().getEnabled()) {
          return;
        } else if (TriggerEnum.webhook.equals(runTrigger) && triggers.getWebhook().getEnabled()) {
          return;
        } else if (TriggerEnum.event.equals(runTrigger) && triggers.getEvent().getEnabled()) {
          Trigger trigger = triggers.getEvent();
          validateTriggerConditions(ParameterUtil.getValue(params, "event"), trigger);
          return;
        } else if (TriggerEnum.github.equals(runTrigger) && triggers.getWebhook().getEnabled()) {
          Trigger trigger = triggers.getWebhook();
          validateTriggerConditions(ParameterUtil.getValue(params, "payload"), trigger);
          return;
        }
        throw new BoomerangException(BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED);
      }
    }
  }

  /*
   * Implements the logic checks for each WorkflowTriggerCondition operation type
   */
  private void validateTriggerConditions(Object data, Trigger trigger) {
    if (!trigger.getConditions().isEmpty()) {
      // Convert Object to JsonNode and configure for JsonPath
      JsonNode jData = JSON_PATH_MAPPER.valueToTree(data);
      Configuration jsonConfig =
          Configuration.builder()
              .mappingProvider(new JacksonMappingProvider())
              .jsonProvider(new JacksonJsonNodeJsonProvider())
              .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
              .build();
      DocumentContext jsonContext = JsonPath.using(jsonConfig).parse(jData);

      // Determine all conditions match
      trigger
          .getConditions()
          .forEach(
              con -> {
                Boolean canRun = Boolean.TRUE;
                String field = jsonContext.read(con.getField());
                switch (con.getOperation()) {
                  case matches -> {
                    canRun = field.matches(con.getValue());
                  }
                  case equals -> {
                    canRun = field.equals(con.getValue());
                  }
                  case in -> {
                    canRun = con.getValues().contains(field);
                  }
                }
                if (!canRun) {
                  throw new BoomerangException(BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED);
                }
              });
    }
  }

  /*
   * Converts from Workflow to Workflow Canvas
   */
  protected WorkflowCanvas convertWorkflowToCanvas(Workflow workflow) {
    List<WorkflowTask> wfTasks = workflow.getTasks();
    WorkflowCanvas wfCanvas = new WorkflowCanvas(workflow);
    List<CanvasNode> nodes = new ArrayList<>();
    List<CanvasEdge> edges = new ArrayList<>();

    Map<String, TaskType> taskNamesToType =
        wfTasks.stream().collect(Collectors.toMap(WorkflowTask::getName, WorkflowTask::getType));
    Map<String, String> taskNameToNodeId = new HashMap<>();

    // Make config the source of truth on the canvas
    wfCanvas.setConfig(workflow.getParams());

    // Create Nodes
    wfTasks.forEach(
        task -> {
          CanvasNode node = new CanvasNode();
          node.setType(task.getType());
          if (task.getAnnotations().containsKey("boomerang.io/position")) {
            Map<String, Number> position =
                (Map<String, Number>) task.getAnnotations().get("boomerang.io/position");
            CanvasNodePosition nodePosition = new CanvasNodePosition();
            nodePosition.setX(position.get("x"));
            nodePosition.setY(position.get("y"));
            LOGGER.info("Node Position:" + nodePosition.toString());
            node.setPosition(nodePosition);
          }
          CanvasNodeData nodeData = new CanvasNodeData();
          nodeData.setName(task.getName());
          nodeData.setParams(task.getParams());
          nodeData.setResults(task.getResults());
          nodeData.setTaskRef(task.getTaskRef());
          nodeData.setTaskVersion(task.getTaskVersion());
          nodeData.setUpgradesAvailable(task.getUpgradesAvailable());
          nodeData.setForeach(task.getForeach());
          nodeData.setWorkspace(task.getWorkspace());
          node.setData(nodeData);
          nodes.add(node);
          taskNameToNodeId.put(task.getName(), node.getId());
        });
    wfCanvas.setNodes(nodes);

    // Creates Edges - depends on nodes as the IDs for each node are used in the edge mapping
    wfTasks.forEach(
        task -> {
          task.getDependencies()
              .forEach(
                  dep -> {
                    CanvasEdge edge = new CanvasEdge();
                    edge.setTarget(taskNameToNodeId.get(task.getName()));
                    edge.setSource(taskNameToNodeId.get(dep.getTaskRef()));
                    edge.setType(
                        taskNamesToType.get(dep.getTaskRef()) != null
                            ? taskNamesToType.get(dep.getTaskRef()).toString()
                            : "");
                    CanvasEdgeData edgeData = new CanvasEdgeData();
                    edgeData.setExecutionCondition(dep.getExecutionCondition());
                    edgeData.setDecisionCondition(dep.getDecisionCondition());
                    edge.setData(edgeData);
                    edges.add(edge);
                  });
        });

    wfCanvas.setEdges(edges);

    return wfCanvas;
  }

  /*
   * Converts from Canvas Workflow to Workflow
   */
  protected Workflow convertCanvasToWorkflow(WorkflowCanvas canvas) {
    LOGGER.debug("Workflow Canvas: " + canvas.toString());
    /*
     * Creates a Workflow from WorkflowCanvas
     *
     * Does not copy / convert the stored Tasks onto the Workflow. If you want the Tasks you need to run
     * workflow.setTasks(TaskMapper.revisionTasksToListOfTasks(wfRevisionEntity.getTasks()));
     */
    Workflow workflow = new Workflow();
    BeanUtils.copyProperties(canvas, workflow);

    // Convert Config to Params for Workflow storage
    workflow.setParams(canvas.getConfig());

    List<CanvasNode> nodes = canvas.getNodes();
    List<CanvasEdge> edges = canvas.getEdges();

    Map<String, String> nodeIdToTaskName =
        nodes.stream().collect(Collectors.toMap(n -> n.getId(), n -> n.getData().getName()));

    nodes.forEach(
        node -> {
          WorkflowTask task = new WorkflowTask();
          task.setName(node.getData().getName());
          task.setType(node.getType());
          Map<String, Number> position = new HashMap<>();
          position.put("x", node.getPosition().getX());
          position.put("y", node.getPosition().getY());
          task.getAnnotations().put("boomerang.io/position", position);
          task.setParams(node.getData().getParams());
          task.setResults(node.getData().getResults());
          task.setTaskRef(node.getData().getTaskRef());
          task.setTaskVersion(node.getData().getTaskVersion());
          task.setForeach(node.getData().getForeach());
          task.setWorkspace(node.getData().getWorkspace());

          List<WorkflowTaskDependency> dependencies = new LinkedList<>();
          edges.stream()
              .filter(e -> e.getTarget().equals(node.getId()))
              .forEach(
                  e -> {
                    WorkflowTaskDependency dep = new WorkflowTaskDependency();
                    dep.setTaskRef(nodeIdToTaskName.get(e.getSource()));
                    dep.setDecisionCondition(e.getData().getDecisionCondition());
                    dep.setExecutionCondition(e.getData().getExecutionCondition());
                    dependencies.add(dep);
                  });
          task.setDependencies(dependencies);
          workflow.getTasks().add(task);
        });
    LOGGER.debug("Converted Workflow: {}", workflow.getName());
    return workflow;
  }

  /*
   * A node's taskRef is the task's id in storage and its slug form on the API; TaskService owns
   * the translation, in one batch per workflow.
   */
  private void convertTaskRefsToSlugs(String team, Workflow workflow) {
    List<WorkflowTask> nodes =
        workflow.getTasks().stream()
            .filter(t -> !t.getName().equals("start") && !t.getName().equals("end"))
            .toList();
    Map<String, String> taskSlugs =
        taskService.slugsByRef(nodes.stream().map(WorkflowTask::getTaskRef).toList());
    nodes.forEach(
        t -> {
          if (!taskSlugs.containsKey(t.getTaskRef())) {
            LOGGER.warn("TaskRef not found: {} : {}", t.getName(), t.getTaskRef());
          }
          t.setTaskRef(taskSlugs.getOrDefault(t.getTaskRef(), ""));
        });
    workflow
        .getTasks()
        .forEach(
            t -> {
              // Convert RunWorkflow and RunScheduledWorkflow Refs to slugs
              if (t.getType().equals(TaskType.runworkflow)
                  || t.getType().equals(TaskType.runscheduledworkflow)) {
                t.getParams()
                    .forEach(
                        param -> {
                          if (param.getName().equals("workflowRef") && param.getValue() != null) {
                            List<String> slugs =
                                relationshipService.filter(
                                    RelationshipType.WORKFLOW,
                                    Optional.of(List.of(param.getValue().toString())),
                                    Optional.of(RelationshipType.WORKSPACE),
                                    Optional.of(List.of(team)));
                            if (slugs == null || slugs.isEmpty()) {
                              throw new BoomerangException(
                                  BoomerangError.WORKFLOW_INVALID_TASK_REF,
                                  t.getName(),
                                  param.getValue());
                            }
                            param.setValue(slugs.get(0));
                          }
                        });
              }
            });
  }

  /*
   * Every node other than start and end must name the Task it runs. Without a reference there is
   * nothing to resolve at save time and nothing to execute at run time, so the workflow is
   * rejected here - at save and again at submit, because a workflow saved before this check
   * could still carry one.
   */
  private static void validateTaskRefsPresent(List<WorkflowTask> tasks) {
    if (tasks == null) {
      return;
    }
    for (WorkflowTask task : tasks) {
      if (!"start".equals(task.getName())
          && !"end".equals(task.getName())
          && (task.getTaskRef() == null || task.getTaskRef().isBlank())) {
        throw new BoomerangException(BoomerangError.WORKFLOW_MISSING_TASK_REF, task.getName());
      }
    }
  }

  // A slug form naming another workspace's task, or no task, refuses the save.
  private void convertTaskSlugsToRefs(String team, Workflow workflow) {
    validateTaskRefsPresent(workflow.getTasks());
    List<WorkflowTask> nodes =
        workflow.getTasks().stream()
            .filter(t -> !t.getName().equals("start") && !t.getName().equals("end"))
            .toList();
    Map<String, String> taskRefs =
        taskService.refsBySlug(team, nodes.stream().map(WorkflowTask::getTaskRef).toList());
    nodes.forEach(
        t -> {
          if (!taskRefs.containsKey(t.getTaskRef())) {
            throw new BoomerangException(
                BoomerangError.WORKFLOW_INVALID_TASK_REF, t.getName(), t.getTaskRef());
          }
          t.setTaskRef(taskRefs.get(t.getTaskRef()));
        });
    workflow
        .getTasks()
        .forEach(
            t -> {
              // Convert RunWorkflow and RunScheduledWorkflow Slugs to Refs
              if (t.getType().equals(TaskType.runworkflow)
                  || t.getType().equals(TaskType.runscheduledworkflow)) {
                t.getParams()
                    .forEach(
                        param -> {
                          if (param.getName().equals("workflowRef") && param.getValue() != null) {
                            List<String> refs =
                                relationshipService.refsInWorkspace(
                                    RelationshipType.WORKFLOW,
                                    team,
                                    Optional.of(List.of(param.getValue().toString())));
                            if (refs == null || refs.isEmpty()) {
                              throw new BoomerangException(
                                  BoomerangError.WORKFLOW_INVALID_TASK_REF,
                                  t.getName(),
                                  param.getValue());
                            }
                            param.setValue(refs.get(0));
                          }
                        });
              }
            });
  }

  // ── Unscoped operations (engine and action callers) ───────────────────────────

  public ResponseEntity<Workflow> get(
      String workflowId, Optional<Integer> version, boolean withTasks) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    final Optional<WorkflowEntity> optWfEntity = workflowRepository.findById(workflowId);
    Optional<WorkflowRevisionEntity> optWfRevisionEntity;
    if (version.isPresent()) {
      optWfRevisionEntity =
          workflowRevisionRepository.findByWorkflowRefAndVersion(workflowId, version.get());
      if (!optWfRevisionEntity.isPresent()) {
        throw new BoomerangException(BoomerangError.WORKFLOW_REVISION_NOT_FOUND);
      }
    } else {
      optWfRevisionEntity =
          workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId);
    }
    if (!optWfEntity.isPresent() || !optWfRevisionEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    Workflow workflow = ConvertUtil.wfEntityToModel(optWfEntity.get(), optWfRevisionEntity.get());
    if (!withTasks) {
      workflow.setTasks(new LinkedList<>());
    }

    // Determine if there are template upgrades available
    areTaskUpgradesAvailable(workflow);

    return ResponseEntity.ok(workflow);
  }

  public Page<Workflow> query(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryIds) {
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    Query query =
        new Query(
            new QueryCriteria()
                .labels(queryLabels)
                .in("status", WorkflowStatus.class, queryStatus, "status")
                .in("id", queryIds)
                .build());
    Pageable pageable = PagingUtil.apply(query, queryLimit, queryPage, sort);

    LOGGER.debug("Query: " + query.toString());
    List<WorkflowEntity> wfEntities = mongoTemplate.find(query, WorkflowEntity.class);

    List<Workflow> workflows = new LinkedList<>();
    wfEntities.forEach(
        e -> {
          LOGGER.debug("Workflow: {}", e.getId());
          Optional<WorkflowRevisionEntity> optWfRevisionEntity =
              workflowRevisionRepository.findByWorkflowRefAndLatestVersion(e.getId());
          if (optWfRevisionEntity.isPresent()) {
            LOGGER.debug(
                "Revision: {} (version {})",
                optWfRevisionEntity.get().getId(),
                optWfRevisionEntity.get().getVersion());
            Workflow w = ConvertUtil.wfEntityToModel(e, optWfRevisionEntity.get());
            // Determine if there are template upgrades available
            areTaskUpgradesAvailable(w);
            workflows.add(w);
          }
        });

    Page<Workflow> pages =
        PagingUtil.page(workflows, pageable, mongoTemplate, query, WorkflowEntity.class);
    LOGGER.debug(pages.toString());
    return pages;
  }

  /*
   * Generates Counts for a given set of filters
   */
  public ResponseEntity<WorkflowCount> count(
      Optional<Date> from,
      Optional<Date> to,
      Optional<List<String>> labels,
      Optional<List<String>> queryWorkflows) {
    Query query =
        new Query(
            new QueryCriteria()
                .between("creationDate", from, to)
                .labels(labels)
                .in("id", queryWorkflows)
                .build());
    LOGGER.debug("Query: " + query.toString());
    List<WorkflowEntity> wfEntities = mongoTemplate.find(query, WorkflowEntity.class);

    // Collate by status count
    Map<String, Long> result =
        wfEntities.stream()
            .collect(groupingBy(v -> getStatusValue(v), Collectors.counting())); // NOSONAR
    result.put("all", Long.valueOf(wfEntities.size()));

    Arrays.stream(WorkflowStatus.values()).forEach(v -> result.putIfAbsent(v.toString(), 0L));

    WorkflowCount wfCount = new WorkflowCount();
    wfCount.setStatus(result);
    return ResponseEntity.ok(wfCount);
  }

  private String getStatusValue(WorkflowEntity v) {
    return v.getStatus() == null ? "no_status" : v.getStatus().toString();
  }

  /*
   * Adds a new Workflow as WorkflowEntity and WorkflowRevisionEntity
   */
  public ResponseEntity<Workflow> create(Workflow request, boolean useId) {
    WorkflowEntity wfEntity = new WorkflowEntity();
    if (useId) {
      wfEntity.setId(request.getId());
    }
    if (request.getName() != null && !request.getName().isBlank()) {
      request.setName(StringUtil.kebabCase(request.getName()));
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    wfEntity.setName(request.getName());
    if (request.getDisplayName() == null || request.getDisplayName().isEmpty()) {
      wfEntity.setDisplayName(request.getName());
    } else {
      wfEntity.setDisplayName(request.getDisplayName());
    }
    wfEntity.setIcon(request.getIcon());
    wfEntity.setDescription(request.getDescription());
    wfEntity.setLabels(request.getLabels());
    // Add System Generated Annotations
    request.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    request.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);
    wfEntity.setAnnotations(request.getAnnotations());
    wfEntity.setStatus(WorkflowStatus.active);
    wfEntity.setTriggers(request.getTriggers());

    WorkflowRevisionEntity wfRevisionEntity = createWorkflowRevisionEntity(request, 1);
    keepSecretValues(wfRevisionEntity, null);
    wfEntity = workflowRepository.save(wfEntity);
    request.setId(wfEntity.getId());
    wfRevisionEntity.setWorkflowRef(wfEntity.getId());
    workflowRevisionRepository.save(wfRevisionEntity);
    paramLayerCache.evictAll();
    // TODO: figure out a better approach to rollback

    Workflow workflow = ConvertUtil.wfEntityToModel(wfEntity, wfRevisionEntity);
    // Determine if there are template upgrades available
    areTaskUpgradesAvailable(workflow);
    LOGGER.debug("Workflow: {} (version {})", workflow.getId(), workflow.getVersion());
    return ResponseEntity.ok(workflow);
  }

  private WorkflowRevisionEntity createWorkflowRevisionEntity(Workflow request, Integer version) {
    WorkflowRevisionEntity wfRevisionEntity = new WorkflowRevisionEntity();
    wfRevisionEntity.setVersion(version);
    ChangeLog changelog = new ChangeLog(version.equals(1) ? CHANGELOG_INITIAL : CHANGELOG_UPDATE);
    if (request.getChangelog() != null) {
      if (request.getChangelog().getAuthor() != null) {
        changelog.setAuthor(request.getChangelog().getAuthor());
      }
      if (request.getChangelog().getReason() != null) {
        changelog.setReason(request.getChangelog().getReason());
      }
      if (request.getChangelog().getDate() != null) {
        changelog.setDate(request.getChangelog().getDate());
      }
    }
    wfRevisionEntity.setChangelog(changelog);
    wfRevisionEntity.setMarkdown(request.getMarkdown());
    wfRevisionEntity.setParams(request.getParams());
    // A save carries only the declared workspace; the legacy workflow-storage marker a v3 upgrade
    // left on an older revision is never copied forward, so saving the Workflow clears it.
    wfRevisionEntity.setWorkspace(request.getWorkspace());
    if (request.getTasks() == null || request.getTasks().isEmpty()) {
      List<WorkflowTask> tasks = new LinkedList<>();
      WorkflowTask startTask = new WorkflowTask();
      startTask.setName("start");
      startTask.setType(TaskType.start);
      tasks.add(startTask);
      WorkflowTask endTask = new WorkflowTask();
      endTask.setName("end");
      endTask.setType(TaskType.end);
      tasks.add(endTask);
      wfRevisionEntity.setTasks(tasks);
    } else {
      wfRevisionEntity.setTasks(request.getTasks());
    }
    wfRevisionEntity.setTimeout(request.getTimeout());
    wfRevisionEntity.setRetries(request.getRetries());

    // Check Task Names are unique
    List<String> filteredNames =
        wfRevisionEntity.getTasks().stream().map(t -> t.getName()).collect(Collectors.toList());
    List<String> uniqueFilteredNames =
        filteredNames.stream().distinct().collect(Collectors.toList());
    LOGGER.debug("Name sizes: {} -> {}", filteredNames, uniqueFilteredNames);
    if (filteredNames.size() != uniqueFilteredNames.size()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_NON_UNIQUE_TASK_NAME);
    }
    // A foreach task's items are named <name>[<index>], so a task name ending in [<digits>] could
    // collide with another task's item on the (workflowRunRef, name) unique index. Any other use of
    // brackets ("Deploy [prod]") is left alone.
    filteredNames.stream()
        .filter(name -> name != null && FOREACH_ITEM_NAME_PATTERN.matcher(name).matches())
        .findFirst()
        .ifPresent(
            name -> {
              throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_TASK_NAME, name);
            });

    // Check Task Template references are valid
    for (WorkflowTask wfTask : wfRevisionEntity.getTasks()) {
      if (TaskType.start.equals(wfTask.getType()) || TaskType.end.equals(wfTask.getType())) {
        // Start and end mark the graph's edges and never run, so they cannot run for each item.
        if (wfTask.getForeach() != null) {
          throw new BoomerangException(
              BoomerangError.WORKFLOW_INVALID_TASK_FOREACH,
              wfTask.getName(),
              "A " + wfTask.getType() + " task cannot run for each item.");
        }
      } else {
        // Shared utility with DAGUtility
        Task taskTemplate = taskService.retrieveAndValidateTask(wfTask);
        wfTask.setTaskVersion(taskTemplate.getVersion());

        // Reject node params the Task Template does not declare
        validateDeclaredParams(wfTask, taskTemplate);
        validateForeach(wfTask, taskTemplate);
        stampSecretParams(wfTask, taskTemplate);
      }
    }
    return wfRevisionEntity;
  }

  /*
   * The save-side partner of filterSensitiveValues, with the one secret write rule
   * (DataAdapterUtil.keepSecretValue): the redaction marker, or no value, keeps the stored value -
   * the workflow param of the same name, and the node param of the same name on the node of the
   * same name in the latest revision - a blank clears it, and anything else replaces it. A node
   * secret left with no value is dropped from the node, so the task's own default applies on a
   * run. Runs after createWorkflowRevisionEntity has stamped the node params secret. The names of
   * the secrets the write changed or cleared go to the audit trail.
   */
  private static void keepSecretValues(
      WorkflowRevisionEntity incoming, WorkflowRevisionEntity stored) {
    DataAdapterUtil.keepParamSpecValueByFieldType(
        incoming.getParams(),
        stored != null ? stored.getParams() : List.of(),
        FieldType.PASSWORD.value());
    if (incoming.getTasks() != null) {
      keepNodeSecretValues(incoming, stored);
    }
    SecretChangeHolder.compare(secretValues(stored), secretValues(incoming));
  }

  // A revision's secret values by name: its own params as params.<name>, each node's as
  // tasks.<node>.<param>.
  private static Map<String, Object> secretValues(WorkflowRevisionEntity revision) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (revision == null) {
      return values;
    }
    values.putAll(
        SecretChangeHolder.secretValues(
            "params.", revision.getParams(), FieldType.PASSWORD.value()));
    if (revision.getTasks() != null) {
      revision.getTasks().stream()
          .filter(task -> task.getName() != null)
          .forEach(
              task ->
                  values.putAll(
                      SecretChangeHolder.secretRunValues(
                          "tasks." + task.getName() + ".", task.getParams(), ParamType.secret)));
    }
    return values;
  }

  private static void keepNodeSecretValues(
      WorkflowRevisionEntity incoming, WorkflowRevisionEntity stored) {
    for (WorkflowTask task : incoming.getTasks()) {
      if (task.getParams() == null) {
        continue;
      }
      List<RunParam> storedParams =
          (stored == null || stored.getTasks() == null)
              ? List.of()
              : stored.getTasks().stream()
                  .filter(s -> s.getName() != null && s.getName().equals(task.getName()))
                  .findFirst()
                  .map(WorkflowTask::getParams)
                  .orElse(List.of());
      DataAdapterUtil.keepRunParamValueByFieldType(
          task.getParams(), storedParams, ParamType.secret);
      task.setParams(
          task.getParams().stream()
              .filter(p -> !(ParamType.secret.equals(p.getType()) && p.getValue() == null))
              .collect(Collectors.toCollection(LinkedList::new)));
    }
  }

  /*
   * Rejects any node param the referenced Task Template does not declare. The engine's merge
   * (ParameterUtil.addUniqueParams, the same match DAGUtility relies on) accepts any key, so an
   * undeclared param becomes an undeclared PARAM_<NAME> env var on the TaskRun container - this
   * is where a typo is caught instead of silently reaching the container.
   *
   * Only enforced once the Template resolves and declares at least one param: several system
   * task types (e.g. runworkflow/runscheduledworkflow's "workflowRef") take node params their
   * Template spec leaves undeclared, so a Template with an empty declared set is left unchecked
   * rather than treated as "no params allowed".
   *
   * <p>A param with no value is accepted: a secret one keeps its stored value (keepSecretValues),
   * and a task that requires a value fails its own run with a message naming the parameter.
   */
  private void validateDeclaredParams(WorkflowTask wfTask, Task taskTemplate) {
    if (wfTask.getParams() != null) {
      wfTask.getParams().stream()
          .map(RunParam::getName)
          .filter(name -> !ParameterUtil.isValidParamName(name))
          .findFirst()
          .ifPresent(
              name -> {
                throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, name);
              });
      // An empty (or absent) value is valid: emptiness can be meaningful, and a substitution can
      // legitimately resolve to empty. A task that requires a value fails its own run with a
      // message naming the parameter (for example runWorkflow's workflowRef).
      // Case/separator-variant duplicates collide as PARAM_<NAME> env vars and, under
      // case-insensitive matching, as references - rejected here rather than at dispatch.
      List<List<String>> collisions =
          ParameterUtil.paramNameCollisions(
              wfTask.getParams().stream().map(RunParam::getName).collect(Collectors.toList()));
      if (!collisions.isEmpty()) {
        throw new BoomerangException(
            BoomerangError.PARAM_NAME_COLLISION,
            collisions.toString(),
            ParameterUtil.envFold(collisions.get(0).get(0)));
      }
    }
    List<AbstractParam> declaredParams = taskTemplate.getSpec().getParams();
    if (declaredParams == null || declaredParams.isEmpty() || wfTask.getParams() == null) {
      return;
    }
    // Case-insensitive (ruled 2026-08-26), matching the engine's resolution and merge.
    List<String> declaredNames =
        declaredParams.stream().map(AbstractParam::getName).collect(Collectors.toList());
    List<String> undeclaredNames =
        wfTask.getParams().stream()
            .map(RunParam::getName)
            .filter(name -> declaredNames.stream().noneMatch(d -> d.equalsIgnoreCase(name)))
            .collect(Collectors.toList());
    if (!undeclaredNames.isEmpty() && TaskType.runworkflow.equals(wfTask.getType())) {
      validateChildParams(wfTask, undeclaredNames);
    } else if (!undeclaredNames.isEmpty()) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_INVALID_TASK_PARAM,
          wfTask.getName(),
          undeclaredNames.toString());
    }
  }

  /*
   * A run-workflow node hands its params to the child run (TaskExecutionService.childParams), so
   * the ones its catalogue task does not declare must be declared by the child workflow's latest
   * revision, the one the child runs. By now workflowRef holds the child's id (convertTaskSlugsToRefs).
   */
  private void validateChildParams(WorkflowTask wfTask, List<String> forwardedNames) {
    Object workflowRef = ParameterUtil.getValue(wfTask.getParams(), "workflowRef");
    List<String> childNames =
        Optional.ofNullable(workflowRef)
            .flatMap(
                ref -> workflowRevisionRepository.findByWorkflowRefAndLatestVersion(ref.toString()))
            .map(WorkflowRevisionEntity::getParams)
            .orElse(List.of())
            .stream()
            .map(AbstractParam::getName)
            .toList();
    List<String> undeclaredNames =
        forwardedNames.stream()
            .filter(name -> childNames.stream().noneMatch(c -> c.equalsIgnoreCase(name)))
            .toList();
    if (!undeclaredNames.isEmpty()) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_INVALID_CHILD_PARAM,
          wfTask.getName(),
          Optional.ofNullable(workflowRef)
              .flatMap(ref -> workflowRepository.findById(ref.toString()))
              .map(WorkflowEntity::getName)
              .orElse(String.valueOf(workflowRef)),
          undeclaredNames.toString());
    }
  }

  /*
   * A node param carries no type of its own; the Task Template declares it. A value given for a
   * param the Template declares as `password` is stamped `secret`, so the stored revision
   * recognises it as a secret and encrypts it at rest (SecretValueMongoListener).
   */
  private static void stampSecretParams(WorkflowTask wfTask, Task taskTemplate) {
    ParameterUtil.stampSecretParams(wfTask.getParams(), taskTemplate.getSpec().getParams());
  }

  /*
   * Rejects a foreach setting the engine could not fan out. Items must be a JSON array literal
   * within the item cap, or one reference that resolves to an array when the task runs (checked
   * against the cap then). Only the types a dispatcher runs repeat per item, and the task must
   * not declare item or index itself: the engine adds both to each item, and they would collide as
   * PARAM_ITEM and PARAM_INDEX.
   */
  private void validateForeach(WorkflowTask wfTask, Task taskTemplate) {
    if (wfTask.getForeach() == null) {
      return;
    }
    if (!FOREACH_TASK_TYPES.contains(wfTask.getType())) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_INVALID_TASK_FOREACH,
          wfTask.getName(),
          "A " + wfTask.getType() + " task cannot run for each item.");
    }
    Object items = wfTask.getForeach().getItems();
    boolean singleReference =
        items instanceof String string && FOREACH_REFERENCE_PATTERN.matcher(string.trim()).matches();
    if (!singleReference && !(items instanceof List)) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_INVALID_TASK_FOREACH,
          wfTask.getName(),
          "Items must be a JSON array or a single reference such as $(params.items).");
    }
    int cap = maxForeachItems();
    if (items instanceof List<?> list && list.size() > cap) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_INVALID_TASK_FOREACH,
          wfTask.getName(),
          "Items has " + list.size() + " entries, over the cap of " + cap + ".");
    }
    List<AbstractParam> declaredParams = taskTemplate.getSpec().getParams();
    if (declaredParams != null
        && declaredParams.stream()
            .map(AbstractParam::getName)
            .anyMatch(name -> "item".equalsIgnoreCase(name) || "index".equalsIgnoreCase(name))) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_INVALID_TASK_FOREACH,
          wfTask.getName(),
          "The task declares a parameter named item or index, which each item receives from the"
              + " engine.");
    }
  }

  // The foreach item cap the engine enforces, or its default when the key is not seeded.
  private int maxForeachItems() {
    try {
      String value =
          settingsService
              .getSettingConfig(
                  TaskExecutionService.WORKFLOWRUN_SETTINGS_KEY,
                  TaskExecutionService.MAX_FOREACH_ITEMS)
              .getValue();
      return NumberUtils.isDigits(value)
          ? Integer.parseInt(value)
          : TaskExecutionService.DEFAULT_MAX_FOREACH_ITEMS;
    } catch (RuntimeException notConfigured) {
      return TaskExecutionService.DEFAULT_MAX_FOREACH_ITEMS;
    }
  }

  // TODO: handle more of the apply i.e. if original has element, and new does not, keep the
  // original element.
  public ResponseEntity<Workflow> apply(Workflow workflow, Boolean replace) {
    // Apply can create new with specified ID if it exists
    // TODO: add check that ID matches required format for MongoDB
    if (workflow.getId() == null
        || workflow.getId().isBlank()
        || workflowRepository.findById(workflow.getId()).isEmpty()) {
      return this.create(workflow, replace);
    }

    // Update the Workflow Entity with new details
    WorkflowEntity workflowEntity = workflowRepository.findById(workflow.getId()).get();
    if (workflow.getName() != null && !workflow.getName().isBlank()) {
      workflowEntity.setName(workflow.getName());
    }
    if (workflow.getStatus() != null) {
      workflowEntity.setStatus(workflow.getStatus());
    }
    if (workflow.getDescription() != null && !workflow.getDescription().isBlank()) {
      workflowEntity.setDescription(workflow.getDescription());
    }
    if (workflow.getDisplayName() != null && !workflow.getDisplayName().isBlank()) {
      workflowEntity.setDisplayName(workflow.getDisplayName());
    }
    if (workflow.getIcon() != null && !workflow.getIcon().isBlank()) {
      workflowEntity.setIcon(workflow.getIcon());
    }
    if (workflow.getLabels() != null && !workflow.getLabels().isEmpty()) {
      if (replace) {
        workflowEntity.setLabels(workflow.getLabels());
      } else {
        workflowEntity.getLabels().putAll(workflow.getLabels());
      }
    }
    if (workflow.getAnnotations() != null && !workflow.getAnnotations().isEmpty()) {
      if (replace) {
        workflowEntity.setAnnotations(workflow.getAnnotations());
      } else {
        workflowEntity.getAnnotations().putAll(workflow.getAnnotations());
      }
    }
    if (!Objects.isNull(workflow.getTriggers())) {
      if (!Objects.isNull(workflow.getTriggers().getManual())) {
        workflowEntity.getTriggers().setManual(workflow.getTriggers().getManual());
      }
      if (!Objects.isNull(workflow.getTriggers().getSchedule())) {
        workflowEntity.getTriggers().setSchedule(workflow.getTriggers().getSchedule());
      }
      if (!Objects.isNull(workflow.getTriggers().getWebhook())) {
        workflowEntity.getTriggers().setWebhook(workflow.getTriggers().getWebhook());
      }
      if (!Objects.isNull(workflow.getTriggers().getEvent())) {
        workflowEntity.getTriggers().setEvent(workflow.getTriggers().getEvent());
      }
      if (!Objects.isNull(workflow.getTriggers().getGithub())) {
        workflowEntity.getTriggers().setGithub(workflow.getTriggers().getGithub());
      }
    }
    // Add System Generated Annotations
    workflowEntity.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    workflowEntity.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);
    workflowRepository.save(workflowEntity);

    // TODO, the creation of new better to include fields available on the old that aren't available
    // on the new.
    WorkflowRevisionEntity workflowRevisionEntity =
        workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflow.getId()).get();
    Integer version = workflowRevisionEntity.getVersion();
    WorkflowRevisionEntity newWorkflowRevisionEntity = null;
    if (!replace) {
      version++;
    }
    newWorkflowRevisionEntity = createWorkflowRevisionEntity(workflow, version);
    keepSecretValues(newWorkflowRevisionEntity, workflowRevisionEntity);
    if (replace) {
      newWorkflowRevisionEntity.setId(workflowRevisionEntity.getId());
    }
    newWorkflowRevisionEntity.setWorkflowRef(workflowRevisionEntity.getWorkflowRef());

    workflowRevisionRepository.save(newWorkflowRevisionEntity);
    paramLayerCache.evictAll();

    Workflow appliedWorkflow =
        ConvertUtil.wfEntityToModel(workflowEntity, newWorkflowRevisionEntity);
    // Determine if there are template upgrades available
    areTaskUpgradesAvailable(appliedWorkflow);
    return ResponseEntity.ok(appliedWorkflow);
  }

  /*
   * Queues the Workflow to be executed (and optionally starts the execution)
   *
   * Trigger will be set to 'Engine' if empty
   */
  public WorkflowRun submit(String workflowId, WorkflowSubmitRequest request, boolean start) {
    return submit(workflowId, request, start, null);
  }

  /*
   * Queues the Workflow to be executed (and optionally starts the execution), internally
   * stamping lineage - see submit(String, String, WorkflowSubmitRequest, boolean, String)
   *
   * Trigger will be set to 'Engine' if empty
   */
  public WorkflowRun submit(
      String workflowId, WorkflowSubmitRequest request, boolean start, String initiatedByRef) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    LOGGER.debug("[{}] Workflow Submit Request Received.", workflowId);
    logPayload(request);
    final Optional<WorkflowEntity> optWorkflow = workflowRepository.findById(workflowId);
    if (optWorkflow.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    WorkflowEntity workflow = optWorkflow.get();

    // Ensure Workflow is active to be able to be executed
    if (!WorkflowStatus.active.equals(workflow.getStatus())) {
      throw new BoomerangException(BoomerangError.WORKFLOW_NOT_ACTIVE);
    }

    Optional<WorkflowRevisionEntity> optWorkflowRevisionEntity;
    if (request.getWorkflowVersion() != null) {
      optWorkflowRevisionEntity =
          workflowRevisionRepository.findByWorkflowRefAndVersion(
              workflowId, request.getWorkflowVersion());
    } else {
      optWorkflowRevisionEntity =
          workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId);
    }

    if (!optWorkflowRevisionEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_REVISION_NOT_FOUND);
    }
    WorkflowRevisionEntity wfRevision = optWorkflowRevisionEntity.get();
    // A revision upgraded from v3 with workflow-level storage enabled cannot run: that storage no
    // longer exists. Saving the Workflow drops the marker, which is the author's acknowledgement.
    if (Boolean.TRUE.equals(wfRevision.getLegacyWorkflowStorage())) {
      throw new BoomerangException(BoomerangError.WORKFLOW_STORAGE_REMOVED);
    }
    validateTaskRefsPresent(wfRevision.getTasks());

    final WorkflowRunEntity wfRunEntity = new WorkflowRunEntity();
    wfRunEntity.setWorkflowRevisionRef(wfRevision.getId());
    wfRunEntity.setWorkflowRef(wfRevision.getWorkflowRef());
    wfRunEntity.setWorkflowVersion(wfRevision.getVersion());
    wfRunEntity.setCreationDate(new Date());
    wfRunEntity.setStatus(RunStatus.notstarted);
    wfRunEntity.getLabels().putAll(workflow.getLabels());
    wfRunEntity.setParams(ParameterUtil.abstractParamToRunParam(wfRevision.getParams()));

    // The run takes the revision's workspace; a request carrying one overrides it, settled the
    // same way a saved one is so the dispatcher never sees shorthand.
    if (request.getWorkspace() != null) {
      request.getWorkspace().settle();
    }
    wfRunEntity.setWorkspace(
        (request.getWorkspace() != null) ? request.getWorkspace() : wfRevision.getWorkspace());
    // Every submit path reaches here, so this is where the run timeout is settled: defaulted when
    // nobody declared one, floored at the revision's critical path of task budgets, then clamped
    // by the owning workspace's run-duration quota.
    wfRunEntity.setTimeout(
        runTimeoutPolicy.resolve(
            request.getTimeout(),
            wfRevision.getTimeout(),
            wfRevision.getTasks(),
            maxWorkflowDuration(owningWorkspaceOrNull(workflowId))));
    if (!Objects.isNull(wfRevision.getRetries()) && wfRevision.getRetries() != 0) {
      wfRunEntity.setRetries(wfRevision.getRetries());
    }

    // Add values from Run Request if Present
    if (request.getLabels() != null && !request.getLabels().isEmpty()) {
      wfRunEntity.getLabels().putAll(request.getLabels());
    }
    if (request.getAnnotations() != null && !request.getAnnotations().isEmpty()) {
      wfRunEntity.getAnnotations().putAll(request.getAnnotations());
    }
    if (request.getParams() != null && !request.getParams().isEmpty()) {
      wfRunEntity.setParams(
          ParameterUtil.addUniqueParams(
              wfRunEntity.getParams(),
              ParameterUtil.suppliedParams(wfRunEntity.getParams(), request.getParams())));
    }
    rejectNonStringSecrets(wfRunEntity.getParams());
    if (!Objects.isNull(request.getRetries()) && request.getRetries() != 0) {
      wfRunEntity.setRetries(request.getRetries());
    }
    if (!Objects.isNull(request.getDebug())) {
      wfRunEntity.setDebug(request.getDebug());
    }
    // Set Trigger
    if (Objects.isNull(request.getTrigger())) {
      wfRunEntity.setTrigger("engine");
    } else {
      wfRunEntity.setTrigger(request.getTrigger().getTrigger());
    }
    // Lineage on the typed field: mirrors the retry path's convention (initiatedByRef + trigger
    // together identify what caused this run). Internal-only - never sourced from the request.
    if (initiatedByRef != null) {
      wfRunEntity.setInitiatedByRef(initiatedByRef);
    }
    // Add System Generated Annotations
    Map<String, Object> annotations = new HashMap<>();
    annotations.put("boomerang.io/generation", "4");
    annotations.put("boomerang.io/kind", "WorkflowRun");
    wfRunEntity.getAnnotations().putAll(annotations);
    return workflowRunService.run(wfRunEntity, start);
  }

  /*
   * A secret is a string only: substitution, redaction and the container environment all treat
   * it as one. Checked on the merged params, so a declared secret and one sent as a secret on the
   * request are held to the same rule - here at submit, and on the start and retry requests
   * (WorkflowRunService). An absent value is allowed, as it is for any param.
   */
  static void rejectNonStringSecrets(List<RunParam> params) {
    if (params == null) {
      return;
    }
    params.stream()
        .filter(p -> ParamType.secret.equals(p.getType()))
        .filter(p -> p.getValue() != null && !(p.getValue() instanceof String))
        .findFirst()
        .ifPresent(
            p -> {
              throw new BoomerangException(BoomerangError.PARAM_SECRET_NOT_STRING, p.getName());
            });
  }

  /*
   * Retrieve all the changelogs and return by version
   */
  public ResponseEntity<List<ChangeLogVersion>> changelog(String workflowId) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    final Optional<WorkflowEntity> optWfEntity = workflowRepository.findById(workflowId);
    if (optWfEntity.isPresent()) {
      List<WorkflowRevisionEntity> wfRevisionEntities =
          workflowRevisionRepository.findByWorkflowRefOrderByVersionAsc(workflowId);
      if (wfRevisionEntities.isEmpty()) {
        throw new BoomerangException(BoomerangError.WORKFLOW_REVISION_NOT_FOUND);
      }
      List<ChangeLogVersion> changelogs = new LinkedList<>();
      wfRevisionEntities.forEach(
          wfRevision -> {
            ChangeLogVersion cl = new ChangeLogVersion();
            cl.setVersion(wfRevision.getVersion());
            cl.setAuthor(wfRevision.getChangelog().getAuthor());
            cl.setReason(wfRevision.getChangelog().getReason());
            cl.setDate(wfRevision.getChangelog().getDate());
            changelogs.add(cl);
          });
      return ResponseEntity.ok(changelogs);
    }

    throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
  }

  /*
   * Tombstones the Workflow: a single status change to deleted, never a cascade. Submit already
   * rejects a non-active Workflow, so new runs stop immediately; the watcher winds down in-flight
   * runs of a deleted Workflow, and the prune sweep hard-deletes once runs finalise. Nothing is
   * destroyed here, so running work is never orphaned.
   */
  public void delete(String workflowId) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    if (!workflowRepository.existsById(workflowId)) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    mongoTemplate.updateFirst(
        Query.query(
            Criteria.where("_id").is(workflowId).and("status").ne(WorkflowStatus.deleted)),
        new Update().set("status", WorkflowStatus.deleted),
        WorkflowEntity.class);
  }

  // This will set both the Workflow and Tasks flags for upgrades available
  private void areTaskUpgradesAvailable(Workflow workflow) {
    for (WorkflowTask t : workflow.getTasks()) {
      Optional<TaskRevisionEntity> task =
          taskRevisionRepository.findByParentRefAndLatestVersion(t.getTaskRef());
      if (task.isPresent()
          && t.getTaskVersion() != null
          && (t.getTaskVersion() < task.get().getVersion())) {
        t.setUpgradesAvailable(true);
        workflow.setUpgradesAvailable(true);
      }
    }
  }

  private void logPayload(WorkflowRunRequest request) {
    // Names only: a param value may be a secret, typed or not yet stamped as one.
    LOGGER.debug(
        "Received Request: params [{}], labels {}",
        ParameterUtil.runParamNames(request.getParams()),
        request.getLabels() == null ? "[]" : request.getLabels().keySet());
  }
}
