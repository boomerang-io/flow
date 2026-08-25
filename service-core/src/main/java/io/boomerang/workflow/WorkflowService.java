package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
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
import io.boomerang.common.util.StringUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
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
import io.boomerang.workspace.FlowQuotaProperties;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.CurrentQuotas;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
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
 *       engine's {@code TaskExecutionService} running a {@code runworkflow} task, {@link
 *       ActionService} resolving an Action's Workflow name, and the loader.
 * </ul>
 *
 * <p>F3 collapsed the former {@code api.WorkspaceWorkflowService} pass-through into this class: the
 * workspace resolution and the operation now live together instead of being split across a service
 * boundary that only existed because the engine used to be a separate deployable (DD-02).
 *
 * <p>E8 mode-gating note (carried over verbatim from the pass-through): intentionally NOT
 * {@code @ConditionalOnFlowMode(STANDALONE)}. Two things require it to stay unconditional:
 * ScheduleJob (schedule package, standalone-only) hard-depends on it for the fire path, and the api
 * mode-matrix row keeps "the same surface, team-&gt;default" in engine mode too (J1 remap deferred
 * to E10) - in particular POST /workspace/&#123;workspace&#125;/workflow/&#123;name&#125;/submit,
 * the only HTTP route that starts a run, must work in engine mode against the single {@code system}
 * workspace (AM-10, EngineWorkspaceInterceptor).
 *
 * <p>Its two standalone-only collaborators are therefore held as ObjectProvider, not as fields:
 * workspace.WorkspaceService and schedule.ScheduleService are both
 * {@code @ConditionalOnFlowMode(STANDALONE)}, so in engine mode neither bean exists. (Same intent as
 * the Optional&lt;IntegrationService&gt; injection in event.WebhookEventService; ObjectProvider
 * rather than Optional because both beans take this one back in their own constructors - Optional
 * resolves eagerly and would close the cycle at construction time.) Every call through them is
 * guarded:
 *
 * <ul>
 *   <li>quotas (canCreateWithQuotas, canRunWithQuotas, the run-duration ceiling in internalSubmit)
 *       run only when the quota subsystem is on - see workspace.FlowQuotaProperties. Off in engine
 *       mode, where the run-duration ceiling falls back to the platform default in the "workspaces"
 *       settings.
 *   <li>schedules (delete, updateScheduleTriggers) are skipped when no ScheduleService bean is
 *       present, because engine mode has no schedule management at all (ruling I2).
 * </ul>
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

  private static final String TASK_REF_SEPERATOR = "/";
  public static final String FEATURES_SETTINGS_KEY = "features";
  public static final String FEATURES_WORKSPACE_QUOTA = "workspaceQuotas";
  public static final String QUOTA_MAX_WORKFLOW_DURATION = "max.workflow.duration";
  public static final String QUOTA_MAX_WORKFLOW_STORAGE = "max.workflow.storage";
  public static final String QUOTA_MAX_WORKFLOWRUN_STORAGE = "max.workflowrun.storage";
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
  private final boolean quotasEnabled;

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
      Environment environment) {
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
    this.quotasEnabled = FlowQuotaProperties.isQuotasEnabled(environment);
  }

  // ── Workspace-scoped operations (the /api/v2 surface) ────────────────────────
  //
  // Every method here performs the SAME relationship call the deleted
  // api.WorkspaceWorkflowService performed, then does the work inline.

  /*
   * Whether quota limits are actually enforced: the quota subsystem has to be on for this
   * flow.mode (FlowQuotaProperties - off in engine mode) AND the operator has to have left the
   * "workspaceQuotas" feature enabled. Short-circuits, so engine mode never reads the setting.
   */
  private boolean quotasEnforced() {
    return quotasEnabled
        && settingsService
            .getSettingConfig(FEATURES_SETTINGS_KEY, FEATURES_WORKSPACE_QUOTA)
            .getBooleanValue();
  }

  /*
   * The ceiling for a WorkflowRun's timeout, in minutes.
   *
   * With the quota subsystem on this is the workspace's max run duration (its own quota override
   * if set, else the platform default). In engine mode there is no WorkspaceService and no
   * per-workspace quota record, so the platform default in the "workspaces" settings document
   * stands on its own - the same value WorkspaceService.getWorkflowMaxDurationForTeam starts from.
   */
  private long maxWorkflowDuration(String team) {
    if (quotasEnabled) {
      return workspaceService.getObject().getWorkflowMaxDurationForTeam(team).longValue();
    }
    return Long.parseLong(
        settingsService
            .getSettingConfig(
                WorkspaceService.WORKSPACES_SETTINGS_KEY,
                WorkspaceService.QUOTA_MAX_WORKFLOWRUN_DURATION)
            .getValue());
  }

  /*
   * Get Worklfow
   *
   * No need to validate params as they are either defaulted or optional
   */
  public Workflow get(String team, String name, Optional<Integer> version, boolean withTasks) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    Workflow workflow = internalGet(team, name, version, withTasks);

    // Filter out sensitive values
    DataAdapterUtil.filterParamSpecValueByFieldType(
        workflow.getParams(), FieldType.PASSWORD.value());

    workflow.setId(null);
    return workflow;
  }

  /*
   * This method is used by the compose methods but ensures the password values are not yet filtered.
   */
  private Workflow internalGet(
      String team, String name, Optional<Integer> version, boolean withTasks) {
    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      Workflow workflow = get(refs.get(0), version, withTasks).getBody();

      // Convert Workflow TaskRefs to Slugs
      convertTaskRefsToSlugs(team, workflow);
      return workflow;
    }
    throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(queryTeam)),
            false);
    LOGGER.debug("Workflow Refs: {}", refs.toString());
    if (refs == null || refs.size() == 0) {
      return new WorkflowResponsePage();
    }

    Page<Workflow> page =
        query(
            queryLimit, queryPage, querySort, queryLabels, queryStatus, Optional.of(refs));
    WorkflowResponsePage response =
        new WorkflowResponsePage(page.getContent(), page.getPageable(), page.getTotalElements());

    LOGGER.debug("Workflow Response: {}", response.toString());
    if (!response.getContent().isEmpty()) {
      response
          .getContent()
          .forEach(
              w -> {
                // Filter out sensitive values
                DataAdapterUtil.filterParamSpecValueByFieldType(
                    w.getParams(), FieldType.PASSWORD.value());
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
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(queryTeam)),
            false);
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
    // Ensure name is in slug format
    if (request.getName() != null && !request.getName().isBlank()) {
      request.setName(StringUtil.kebabCase(request.getName()));
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    // Fill in displayName if not set
    validateAndSetDisplayName(request);
    LOGGER.debug("Workflow DisplayName: {}", request.getDisplayName());

    // Ensure Workflow name is unique within Workspace
    List<String> existingWorkflowRefs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(request.getName())),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!existingWorkflowRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    // Check creation quotas
    canCreateWithQuotas(team);

    // Default Triggers
    validateTriggerDefaults(request);

    // Default Workspaces
    setUpWorkspaceDefaults(request);

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
    DataAdapterUtil.filterParamSpecValueByFieldType(
        workflow.getParams(), FieldType.PASSWORD.value());

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

  private void setUpWorkspaceDefaults(Workflow request) {
    boolean enforceQuotas = quotasEnforced();
    if (request.getWorkspaces() != null && !request.getWorkspaces().isEmpty()) {
      // Workflow Storage
      for (WorkflowWorkspace ws : request.getWorkspaces()) {
        if (ws.getType().equals("workflow")) {
          String maxStorageSizeQuota =
              this.settingsService
                  .getSettingConfig(WorkspaceService.WORKSPACES_SETTINGS_KEY, QUOTA_MAX_WORKFLOW_STORAGE)
                  .getValue()
                  .replace("Gi", "");
          ws.setName("workflow");
          ws.setOptional(false);
          WorkflowWorkspaceSpec workflowWorkspaceSpec = new WorkflowWorkspaceSpec();
          if (ws.getSpec() != null) {
            //            workflowWorkspaceSpec = (WorkflowWorkspaceSpec) ws.getSpec();
            BeanUtils.copyProperties(ws.getSpec(), workflowWorkspaceSpec);
          }
          if (workflowWorkspaceSpec.getSize() == null) {
            workflowWorkspaceSpec.setSize(maxStorageSizeQuota);
          } else if (enforceQuotas
              && (Integer.valueOf(workflowWorkspaceSpec.getSize())
                  > Integer.valueOf(maxStorageSizeQuota))) {
            throw new BoomerangException(
                BoomerangError.QUOTA_EXCEEDED,
                "Workspace Size Limit",
                workflowWorkspaceSpec.getSize(),
                maxStorageSizeQuota);
          }
          ws.setSpec(workflowWorkspaceSpec);
        } else if (ws.getType().equals("workflowrun")) {
          String maxStorageSizeQuota =
              this.settingsService
                  .getSettingConfig(WorkspaceService.WORKSPACES_SETTINGS_KEY, QUOTA_MAX_WORKFLOWRUN_STORAGE)
                  .getValue()
                  .replace("Gi", "");
          ws.setName("workflowrun");
          ws.setOptional(false);
          WorkflowWorkspaceSpec workflowWorkspaceSpec = new WorkflowWorkspaceSpec();
          if (ws.getSpec() != null) {
            BeanUtils.copyProperties(ws.getSpec(), workflowWorkspaceSpec);
            //            workflowWorkspaceSpec = (WorkflowWorkspaceSpec) ws.getSpec();
          }
          if (workflowWorkspaceSpec.getSize() == null) {
            workflowWorkspaceSpec.setSize(maxStorageSizeQuota);
          } else if (enforceQuotas
              && (Integer.valueOf(workflowWorkspaceSpec.getSize())
                  > Integer.valueOf(maxStorageSizeQuota))) {
            throw new BoomerangException(
                BoomerangError.QUOTA_EXCEEDED,
                "Workspace Size Limit",
                workflowWorkspaceSpec.getSize(),
                maxStorageSizeQuota);
          }
          ws.setSpec(workflowWorkspaceSpec);
        }
      }
    }
  }

  /*
   * Apply allows you to create a new version or override an existing Workflow as well as create new
   * Workflow with supplied ID
   */
  public Workflow apply(String team, Workflow workflow, boolean replace) {
    if (workflow != null && workflow.getName() != null && !workflow.getName().isBlank()) {
      List<String> refs =
          relationshipService.filter(
              RelationshipType.WORKFLOW,
              Optional.of(List.of(workflow.getName())),
              Optional.of(RelationshipType.WORKSPACE),
              Optional.of(List.of(team)),
              false);
      if (!refs.isEmpty()) {
        workflow.setId(refs.get(0));

        // Fill in displayName if not set
        validateAndSetDisplayName(workflow);

        // Update Schedule Triggers
        updateScheduleTriggers(
            team,
            workflow,
            this.get(team, workflow.getName(), Optional.empty(), false).getTriggers());

        // Default Triggers
        validateTriggerDefaults(workflow);

        // Convert TaskSlugs to Refs(IDs)
        convertTaskSlugsToRefs(team, workflow);

        // TODO go through and ensure all the required ParamSpec elements are set

        Workflow appliedWorkflow = apply(workflow, replace).getBody();

        // Filter out sensitive values
        DataAdapterUtil.filterParamSpecValueByFieldType(
            appliedWorkflow.getParams(), FieldType.PASSWORD.value());

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
    throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
  }

  /*
   * Submit Workflow to Run
   */
  public WorkflowRun submit(
      String team, String name, WorkflowSubmitRequest request, boolean start) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      return this.internalSubmit(team, refs.get(0), request, start);
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
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.empty(),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
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
    // Check if Workflow exists and is active. Then check triggers are enabled.
    // Presumed workflow exists as relationship was valid to get to this point.
    Workflow workflow = get(workflowId, Optional.empty(), false).getBody();
    // Check Triggers - Throws Exception - Check first, as if trigger not enabled, no point in
    // checking quotas
    canRunWithTrigger(workflow.getTriggers(), request.getTrigger(), request.getParams());
    // Check Quotas - Throws Exception
    canRunWithQuotas(team, Optional.of(request.getWorkspaces()));
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
    // Set Workflow Timeout
    Long timeout = maxWorkflowDuration(team);
    if (!Objects.isNull(request.getTimeout()) && request.getTimeout() < timeout) {
      timeout = request.getTimeout();
    }
    request.setTimeout(Long.valueOf(timeout));
    // These annotations are processed by the DAGUtility in the Engine
    Map<String, Object> executionAnnotations = new HashMap<>();
    executionAnnotations.put(
        "boomerang.io/task-deletion",
        this.settingsService.getSettingConfig(TASK_SETTINGS_KEY, "deletion.policy").getValue());
    executionAnnotations.put(
        "boomerang.io/task-default-image",
        this.settingsService.getSettingConfig(TASK_SETTINGS_KEY, "default.image").getValue());
    executionAnnotations.put(
        "boomerang.io/task-timeout",
        this.settingsService.getSettingConfig(TASK_SETTINGS_KEY, "default.timeout").getValue());

    // Add Context, Global, and Workspace parameters to the WorkflowRun request
    ParamLayers paramLayers = paramLayerService.buildParamLayers(team, workflow);
    executionAnnotations.put("boomerang.io/global-params", paramLayers.getGlobalParams());
    executionAnnotations.put("boomerang.io/context-params", paramLayers.getContextParams());
    executionAnnotations.put("boomerang.io/workspace-params", paramLayers.getTeamParams());

    // Add Contextual Information such as team-name. Used by Engine and the AcquireTaskLock and
    // other tasks to add a hidden prefix.
    executionAnnotations.put("boomerang.io/workspace-name", team);
    request.getAnnotations().putAll(executionAnnotations);

    WorkflowRun wfRun = submit(workflowId, request, start);

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
    return wfRun;
  }

  /*
   * Retrieve a workflows changelog from all versions
   */
  public ResponseEntity<List<ChangeLogVersion>> changelog(String team, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      return ResponseEntity.ok(changelog(refs.get(0)).getBody());
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
  }

  /*
   * Delete the Workflows, WorkflowRuns, and TaskRuns by calling Engine.
   *
   * Engine takes care of deleting Triggers & Workspaces
   *
   * We have to delete the Actions, Schedules, Tokens, and Relationships
   */
  public void delete(String team, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    List<String> refs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      // Deletes the Workflow and associated WorkflowRuns, and TaskRuns
      delete(refs.get(0));
      // Engine mode has no schedule management (ruling I2) - no ScheduleService bean, and no
      // schedules to delete.
      scheduleService.ifAvailable(s -> s.deleteAllForWorkflow(refs.get(0)));
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

      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();

      byte[] buf = mapper.writeValueAsBytes(workflow);

      return ResponseEntity.ok()
          .contentLength(buf.length)
          .contentType(MediaType.parseMediaType("application/octet-stream"))
          .body(new InputStreamResource(new ByteArrayInputStream(buf)));
    } catch (IOException e) {

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
    final Workflow response = this.get(team, name, Optional.empty(), true);
    Workflow workflow = response;
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
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    final Workflow response = this.internalGet(team, name, version, true);
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
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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
    if (Objects.isNull(workflow.getTriggers())) {
      // Manual trigger will be set to Enable = true.
      workflow.setTriggers(new WorkflowTrigger());
    }
    LOGGER.debug("Triggers: " + workflow.getTriggers());
    // Default to enabled for Workflows
    if (Objects.isNull(workflow.getTriggers().getManual())) {
      workflow.getTriggers().setManual(new Trigger(Boolean.TRUE));
    }
    if (Objects.isNull(workflow.getTriggers().getSchedule())) {
      workflow.getTriggers().setSchedule(new Trigger(Boolean.FALSE));
    }
    if (Objects.isNull(workflow.getTriggers().getWebhook())) {
      workflow.getTriggers().setWebhook(new Trigger(Boolean.FALSE));
    }
    if (Objects.isNull(workflow.getTriggers().getEvent())) {
      workflow.getTriggers().setEvent(new Trigger(Boolean.FALSE));
    }
    if (Objects.isNull(workflow.getTriggers().getGithub())) {
      workflow.getTriggers().setGithub(new Trigger(Boolean.FALSE));
    }
  }

  /*
   * Determine if Schedules need to be disabled based on triggers
   */
  private void updateScheduleTriggers(
      final String team, final Workflow request, WorkflowTrigger currentTriggers) {
    // Engine mode has no schedule management (ruling I2) - no ScheduleService bean, and no
    // schedules whose trigger state could need syncing.
    ScheduleService schedules = scheduleService.getIfAvailable();
    if (schedules == null) {
      return;
    }
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
   * Check if the Workspace Quotas allow a Workflow to run
   */
  private void canCreateWithQuotas(String team) {
    if (quotasEnforced()) {
      CurrentQuotas quotas = workspaceService.getObject().getCurrentQuotas(team);
      LOGGER.debug("Quotas: {}", quotas.toString());
      if (quotas.getCurrentWorkflowCount() > quotas.getMaxWorkflowCount()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Number of Workflows",
            quotas.getCurrentWorkflowCount(),
            quotas.getMaxWorkflowCount());
      }
    }
  }

  /*
   * Check if the Workspace Quotas allow a Workflow to run
   */
  private void canRunWithQuotas(String team, Optional<List<WorkflowWorkspace>> workspaces) {
    if (quotasEnforced()) {
      CurrentQuotas quotas = workspaceService.getObject().getCurrentQuotas(team);
      LOGGER.debug("Quotas: {}", quotas.toString());
      if (quotas.getCurrentConcurrentRuns() > quotas.getMaxConcurrentRuns()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Concurrent runs (executions)",
            quotas.getCurrentConcurrentRuns(),
            quotas.getMaxConcurrentRuns());
      } else if (quotas.getCurrentRuns() > quotas.getMaxWorkflowRunMonthly()) {
        throw new BoomerangException(
            BoomerangError.QUOTA_EXCEEDED,
            "Number of runs (executions)",
            quotas.getCurrentRuns(),
            quotas.getMaxWorkflowRunMonthly());
      } else if (workspaces.isPresent()
          && !workspaces.get().isEmpty()
          && workspaces.get().size() > 0) {
        workspaces
            .get()
            .forEach(
                ws -> {
                  if (ws.getType().equals("workflow") && ws.getSpec() != null) {
                    try {
                      Field sizeField = ws.getSpec().getClass().getDeclaredField("size");
                      String size = (String) sizeField.get(ws.getSpec());
                      if (Integer.valueOf(size) > quotas.getMaxWorkflowStorage()) {
                        throw new BoomerangException(
                            BoomerangError.QUOTA_EXCEEDED,
                            "Requested Workspace size",
                            size,
                            quotas.getMaxWorkflowStorage());
                      }
                    } catch (NoSuchFieldException | IllegalAccessException ex) {
                      // Do nothing
                    }
                  } else if (ws.getType().equals("workflowrun") && ws.getSpec() != null) {
                    try {
                      Field sizeField = ws.getSpec().getClass().getDeclaredField("size");
                      String size = (String) sizeField.get(ws.getSpec());
                      if (Integer.valueOf(size) > quotas.getMaxWorkflowRunStorage()) {
                        throw new BoomerangException(
                            BoomerangError.QUOTA_EXCEEDED,
                            "Requested Workspace size",
                            size,
                            quotas.getMaxWorkflowRunStorage());
                      }
                    } catch (NoSuchFieldException | IllegalAccessException ex) {
                      // Do nothing
                    }
                  }
                });
      }
    }
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
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      JsonNode jData = mapper.valueToTree(data);
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
    LOGGER.debug("Converted Workflow: " + workflow.toString());
    return workflow;
  }

  /*
   * Helper methods to from TaskRef to TaskSlug and vice versa
   *
   * Duplicated in WorkflowRunService.impl
   */
  private void convertTaskRefsToSlugs(String team, Workflow workflow) {
    workflow
        .getTasks()
        .forEach(
            t -> {
              // Convert the task ref to a slug
              if (!t.getName().equals("start") && !t.getName().equals("end")) {
                Boolean isTeamTask = false;
                // Check for global task
                List<String> slugs =
                    relationshipService.filter(
                        RelationshipType.TASK,
                        Optional.of(List.of(t.getTaskRef())),
                        Optional.empty(),
                        Optional.empty());
                if (slugs.isEmpty()) {
                  isTeamTask = true;
                  // Check for team task
                  slugs =
                      relationshipService.filter(
                          RelationshipType.TEAMTASK,
                          Optional.of(List.of(t.getTaskRef())),
                          Optional.of(RelationshipType.WORKSPACE),
                          Optional.of(List.of(team)));
                }
                if (slugs.isEmpty()) {
                  LOGGER.warn("TaskRef not found: {} : {}", t.getName(), t.getTaskRef());
                  t.setTaskRef("");
                } else {
                  t.setTaskRef(
                      isTeamTask ? team + TASK_REF_SEPERATOR + slugs.get(0) : slugs.get(0));
                }
              }
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
                                  BoomerangError.TASK_INVALID_REF, t.getName());
                            }
                            param.setValue(slugs.get(0));
                          }
                        });
              }
            });
  }

  private void convertTaskSlugsToRefs(String team, Workflow workflow) {
    workflow
        .getTasks()
        .forEach(
            t -> {
              if (!t.getName().equals("start") && !t.getName().equals("end")) {
                List<String> refs;
                if (t.getTaskRef().contains(TASK_REF_SEPERATOR)) {
                  refs =
                      relationshipService.filter(
                          RelationshipType.TEAMTASK,
                          Optional.of(List.of(t.getTaskRef().split(TASK_REF_SEPERATOR)[1])),
                          Optional.of(RelationshipType.WORKSPACE),
                          Optional.of(List.of(team)),
                          false);
                } else {
                  refs =
                      relationshipService.filter(
                          RelationshipType.TASK,
                          Optional.of(List.of(t.getTaskRef())),
                          Optional.empty(),
                          Optional.empty(),
                          false);
                }
                if (refs.isEmpty()) {
                  throw new BoomerangException(
                      BoomerangError.WORKFLOW_INVALID_TASK_REF, t.getName(), t.getTaskRef());
                }
                t.setTaskRef(refs.get(0));
              }
              // Convert RunWorkflow and RunScheduledWorkflow Slugs to Refs
              if (t.getType().equals(TaskType.runworkflow)
                  || t.getType().equals(TaskType.runscheduledworkflow)) {
                t.getParams()
                    .forEach(
                        param -> {
                          if (param.getName().equals("workflowRef") && param.getValue() != null) {
                            List<String> refs =
                                relationshipService.filter(
                                    RelationshipType.WORKFLOW,
                                    Optional.of(List.of(param.getValue().toString())),
                                    Optional.of(RelationshipType.WORKSPACE),
                                    Optional.of(List.of(team)),
                                    false);
                            if (refs == null || refs.isEmpty()) {
                              throw new BoomerangException(
                                  BoomerangError.TASK_INVALID_REF, t.getName());
                            }
                            param.setValue(refs.get(0));
                          }
                        });
              }
            });
  }

  // ── Unscoped operations (engine, action and loader callers) ───────────────────

  public ResponseEntity<Workflow> get(
      String workflowId, Optional<Integer> version, boolean withTasks) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryStatus.isPresent()) {
      if (queryStatus.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(WorkflowStatus.class, q))) {
        Criteria criteria = Criteria.where("status").in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    if (queryIds.isPresent()) {
      Criteria criteria = Criteria.where("id").in(queryIds.get());
      criteriaList.add(criteria);
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    if (queryLimit.isPresent()) {
      query.with(pageable);
    } else {
      query.with(sort);
    }

    LOGGER.debug("Query: " + query.toString());
    List<WorkflowEntity> wfEntities = mongoTemplate.find(query, WorkflowEntity.class);

    List<Workflow> workflows = new LinkedList<>();
    wfEntities.forEach(
        e -> {
          LOGGER.debug("Workflow: " + e.toString());
          Optional<WorkflowRevisionEntity> optWfRevisionEntity =
              workflowRevisionRepository.findByWorkflowRefAndLatestVersion(e.getId());
          if (optWfRevisionEntity.isPresent()) {
            LOGGER.debug("Revision: " + optWfRevisionEntity.get().toString());
            Workflow w = ConvertUtil.wfEntityToModel(e, optWfRevisionEntity.get());
            // Determine if there are template upgrades available
            areTaskUpgradesAvailable(w);
            workflows.add(w);
          }
        });

    Page<Workflow> pages =
        PageableExecutionUtils.getPage(workflows, pageable, () -> workflows.size());
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
    List<Criteria> criteriaList = new ArrayList<>();

    if (from.isPresent() && !to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get());
      criteriaList.add(criteria);
    } else if (!from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").lt(to.get());
      criteriaList.add(criteria);
    } else if (from.isPresent() && to.isPresent()) {
      Criteria criteria = Criteria.where("creationDate").gte(from.get()).lt(to.get());
      criteriaList.add(criteria);
    }

    // TODO add the ability to OR labels not just AND
    if (labels.isPresent()) {
      labels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryWorkflows.isPresent()) {
      Criteria criteria = Criteria.where("id").in(queryWorkflows.get());
      criteriaList.add(criteria);
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
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
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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
    wfEntity = workflowRepository.save(wfEntity);
    request.setId(wfEntity.getId());
    wfRevisionEntity.setWorkflowRef(wfEntity.getId());
    workflowRevisionRepository.save(wfRevisionEntity);
    // TODO: figure out a better approach to rollback

    Workflow workflow = ConvertUtil.wfEntityToModel(wfEntity, wfRevisionEntity);
    // Determine if there are template upgrades available
    areTaskUpgradesAvailable(workflow);
    LOGGER.debug(workflow.toString());
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
    wfRevisionEntity.setWorkspaces(request.getWorkspaces());
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

    // Check Task Template references are valid
    for (WorkflowTask wfTask : wfRevisionEntity.getTasks()) {
      if (!TaskType.start.equals(wfTask.getType()) && !TaskType.end.equals(wfTask.getType())) {

        // Shared utility with DAGUtility
        Task taskTemplate = taskService.retrieveAndValidateTask(wfTask);
        wfTask.setTaskVersion(taskTemplate.getVersion());
      }
    }
    return wfRevisionEntity;
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
    if (replace) {
      newWorkflowRevisionEntity.setId(workflowRevisionEntity.getId());
    }
    newWorkflowRevisionEntity.setWorkflowRef(workflowRevisionEntity.getWorkflowRef());

    workflowRevisionRepository.save(newWorkflowRevisionEntity);

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
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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

    final WorkflowRunEntity wfRunEntity = new WorkflowRunEntity();
    wfRunEntity.setWorkflowRevisionRef(wfRevision.getId());
    wfRunEntity.setWorkflowRef(wfRevision.getWorkflowRef());
    wfRunEntity.setWorkflowVersion(wfRevision.getVersion());
    wfRunEntity.setCreationDate(new Date());
    wfRunEntity.setStatus(RunStatus.notstarted);
    wfRunEntity.getLabels().putAll(workflow.getLabels());
    wfRunEntity.setParams(ParameterUtil.abstractParamToRunParam(wfRevision.getParams()));

    wfRunEntity.setWorkspaces(wfRevision.getWorkspaces());
    if (!Objects.isNull(wfRevision.getTimeout()) && wfRevision.getTimeout() != 0) {
      wfRunEntity.setTimeout(wfRevision.getTimeout());
    }
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
          ParameterUtil.addUniqueParams(wfRunEntity.getParams(), request.getParams()));
    }
    if (request.getWorkspaces() != null && !request.getWorkspaces().isEmpty()) {
      wfRunEntity.getWorkspaces().addAll(request.getWorkspaces());
    }
    if (!Objects.isNull(request.getTimeout()) && request.getTimeout() != 0) {
      wfRunEntity.setTimeout(request.getTimeout());
    }
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
    // Add System Generated Annotations
    Map<String, Object> annotations = new HashMap<>();
    annotations.put("boomerang.io/generation", "4");
    annotations.put("boomerang.io/kind", "WorkflowRun");
    wfRunEntity.getAnnotations().putAll(annotations);
    return workflowRunService.run(wfRunEntity, start);
  }

  /*
   * Retrieve all the changelogs and return by version
   */
  public ResponseEntity<List<ChangeLogVersion>> changelog(String workflowId) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    final Optional<WorkflowEntity> optWfEntity = workflowRepository.findById(workflowId);
    if (optWfEntity.isPresent()) {
      List<WorkflowRevisionEntity> wfRevisionEntities =
          workflowRevisionRepository.findByWorkflowRef(workflowId);
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
   * runs of a deleted Workflow, and a retention sweep prunes once runs finalise. Nothing is
   * destroyed here, so running work is never orphaned.
   */
  public void delete(String workflowId) {
    if (workflowId == null || workflowId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
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
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      String payload = objectMapper.writeValueAsString(request);
      LOGGER.debug("Payload: {}", payload);
    } catch (JacksonException e) {
      LOGGER.error(e.getStackTrace());
    }
  }
}
