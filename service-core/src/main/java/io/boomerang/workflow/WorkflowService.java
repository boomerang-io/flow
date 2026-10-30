package io.boomerang.workflow;

import static java.util.stream.Collectors.groupingBy;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
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
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.model.WorkflowTemplateRef;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.*;
import io.boomerang.engine.DAGUtility;
import io.boomerang.engine.EngineConstants;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.common.util.StorageQuantityUtil;
import io.boomerang.common.util.StringUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.audit.SecretChangeHolder;
import io.boomerang.core.entity.RelationshipNodeEntity;
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
import io.boomerang.workspace.model.Quotas;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * <p>schedule.ScheduleService is held as an ObjectProvider, not as a field: it takes this service
 * back in its own constructor, and Optional would resolve eagerly and close the cycle at
 * construction time.
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
  private final QuotaService quotaService;
  private final RunTimeoutPolicy runTimeoutPolicy;
  private final ObjectMapper objectMapper;
  private final ParamLayerCache paramLayerCache;

  // json-path (Configuration/JacksonMappingProvider/JacksonJsonNodeJsonProvider) only ships
  // Jackson 2 SPIs - com.fasterxml.jackson.databind.ObjectMapper, not the Boot-managed Jackson 3
  // bean - so validateTriggerConditions() needs its own Jackson 2 mapper.
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON_PATH_MAPPER =
      com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

  private static final Configuration JSON_PATH_CONFIGURATION =
      Configuration.builder()
          .mappingProvider(new JacksonMappingProvider())
          .jsonProvider(new JacksonJsonNodeJsonProvider())
          .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
          .build();

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
      QuotaService quotaService,
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
    this.quotaService = quotaService;
    this.runTimeoutPolicy = runTimeoutPolicy;
    this.objectMapper = objectMapper;
    this.paramLayerCache = paramLayerCache;
  }

  // ── Workspace-scoped operations (the /api/v2 surface) ────────────────────────
  //
  // Every method here performs the SAME relationship call the deleted
  // api.WorkspaceWorkflowService performed, then does the work inline.

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
   * Get Worklfow
   *
   * No need to validate params as they are either defaulted or optional
   */
  public Workflow get(String workspace, String name, Optional<Integer> version, boolean withTasks) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    Workflow workflow = get(workflowRef(workspace, name), version, withTasks).getBody();

    // Filter out sensitive values - before the slug conversion, while the task refs still resolve
    filterSensitiveValues(List.of(workflow));

    // Convert Workflow TaskRefs to Slugs
    convertTaskRefsToSlugs(workspace, workflow);

    workflow.setId(null);
    return workflow;
  }

  /*
   * The unfiltered read, for duplicate only: a copy must carry the stored secrets, not the marker.
   */
  private Workflow internalGet(
      String workspace, String name, Optional<Integer> version, boolean withTasks) {
    Workflow workflow = get(workflowRef(workspace, name), version, withTasks).getBody();

    // Convert Workflow TaskRefs to Slugs
    convertTaskRefsToSlugs(workspace, workflow);
    return workflow;
  }

  // The workspace's workflow of the name, by the caller's walk; failing that, the template or
  // starter of the name every workspace may read. Neither is WORKFLOW_INVALID_REF.
  private String workflowRef(String workspace, String name) {
    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      return refs.get(0);
    }
    return sharedForWorkspace(workspace, name)
        .map(WorkflowEntity::getId)
        .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF));
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
    return query(
        queryTeam,
        queryLimit,
        queryPage,
        querySort,
        queryLabels,
        queryStatus,
        queryWorkflows,
        Optional.empty());
  }

  /*
   * The workspace's workflows and, beside them, the templates and starters it has not made its
   * own; `types` narrows to the stored type (workflow, template, starter).
   */
  public WorkflowResponsePage query(
      String queryTeam,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryWorkflows,
      Optional<List<String>> queryTypes) {

    // Get Refs that request has access to
    List<String> refs =
        new ArrayList<>(
            relationshipService.refsInWorkspace(
                RelationshipType.WORKFLOW, queryTeam, queryWorkflows));
    refs.addAll(availableSharedRefs(queryTeam, queryWorkflows));
    LOGGER.debug("Workflow Refs: {}", refs.toString());
    if (refs.isEmpty()) {
      return new WorkflowResponsePage();
    }

    Page<Workflow> page =
        query(
            queryLimit,
            queryPage,
            querySort,
            queryLabels,
            queryStatus,
            Optional.of(refs),
            queryTypes,
            Optional.empty());
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
  public Workflow create(String workspace, Workflow request) {
    // Validate Access - same guard as TaskService.create: the caller must reach the workspace the
    // ownership edge below is written into. Guards every create-shaped route: create, apply and
    // composeApply (when the Workflow does not exist yet) and duplicate all funnel here.
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }
    return createOwned(workspace, request);
  }

  /*
   * Create a Workflow the workspace owns, with no check that the caller reaches the workspace:
   * the caller has checked (create), or is the engine adopting a link for a run (adopt).
   */
  private Workflow createOwned(String workspace, Workflow request) {
    // Ensure name is in slug format
    if (request.getName() != null && !request.getName().isBlank()) {
      request.setName(StringUtil.kebabCase(request.getName()));
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    // Fill in displayName if not set
    validateAndSetDisplayName(request);
    LOGGER.debug("Workflow DisplayName: {}", request.getDisplayName());

    // A template or a starter lives only in the system workspace; a link names a template there.
    validateTypeForWorkspace(workspace, request);
    resolveTemplateRef(request);

    // Ensure Workflow name is unique within Workspace
    if (!ownedWorkflowRefs(workspace, Optional.of(request.getName())).isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    // Check creation quotas
    quotaService.assertCanCreateWorkflow(workspace);

    // Default Triggers
    validateTriggerDefaults(request);

    // Default Workspace
    applyWorkspaceDefaults(workspace, request);

    // Convert TaskRefs to IDs
    convertTaskSlugsToRefs(workspace, request);

    request.setId(null);

    Workflow workflow = create(request, false).getBody();
    LOGGER.debug("Workflow DisplayName: {}", workflow.getDisplayName());

    // Create Relationship
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
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
    convertTaskRefsToSlugs(workspace, workflow);

    // Remove ID from Workflow
    workflow.setId(null);
    return workflow;
  }

  /*
   * A concurrency limit is at least one run; a task's retry setting asks for between zero and the
   * engine's ceiling of attempts, with any backoff wait positive. Anything else is a bad request.
   */
  private static void validateRunSettings(WorkflowRevisionEntity wfRevisionEntity) {
    WorkflowConcurrency concurrency = wfRevisionEntity.getConcurrency();
    if (concurrency != null && concurrency.getLimit() != null && concurrency.getLimit() < 1) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    for (WorkflowTask task : wfRevisionEntity.getTasks()) {
      WorkflowTaskRetry retry = task.getRetry();
      if (retry == null) {
        continue;
      }
      if (retry.getMax() != null
          && (retry.getMax() < 0 || retry.getMax() > EngineConstants.MAX_FAILURE_RETRIES)) {
        throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
      }
      RetryBackoff backoff = retry.getBackoff();
      if (backoff != null
          && ((backoff.getInitial() != null && backoff.getInitial() < 1)
              || (backoff.getMax() != null && backoff.getMax() < 1))) {
        throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
      }
    }
  }

  private static void validateAndSetDisplayName(Workflow request) {
    if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
      request.setDisplayName(request.getName());
    }
  }

  /*
   * Settles the Workflow's workspace on every save (create and update alike): a blank size takes
   * the platform's storage quota, a blank mountPath takes the default, and - only when quotas are
   * enforced - the owning workspace's own storage quota lowers the blank fill and refuses an
   * authored size above it, the same ceiling a run submission is held to; a quota of 0 is no
   * ceiling. The size is then written as a Kubernetes quantity ("1Gi", "500Mi"): a bare number
   * is the Gi shorthand authors use, and the dispatcher passes the value straight to a Kubernetes
   * Quantity, which would read a bare number as bytes. A Workflow without a workspace has no
   * shared storage and nothing to settle.
   */
  private void applyWorkspaceDefaults(String workspace, Workflow request) {
    WorkflowWorkspace declared = request.getWorkspace();
    if (declared == null) {
      return;
    }
    Integer maxSizeGi =
        quotaService.enforced() ? quotaService.maxWorkspaceStorage(workspace) : null;
    if (declared.getSize() == null || declared.getSize().isBlank()) {
      String platformSize = quotaService.defaultWorkspaceStorage();
      declared.setSize(
          Quotas.limited(maxSizeGi) && maxSizeGi < StorageQuantityUtil.toGi(platformSize)
              ? maxSizeGi + "Gi"
              : platformSize);
    } else if (Quotas.limited(maxSizeGi)
        && StorageQuantityUtil.toGi(declared.getSize()) > maxSizeGi) {
      throw new BoomerangException(
          BoomerangError.QUOTA_EXCEEDED, "Workspace Size Limit", declared.getSize(), maxSizeGi);
    }
    declared.settle();
  }

  /*
   * Apply allows you to create a new version or override an existing Workflow as well as create new
   * Workflow with supplied ID
   */
  public Workflow apply(String workspace, Workflow workflow, boolean replace) {
    if (workflow != null && workflow.getName() != null && !workflow.getName().isBlank()) {
      List<String> refs =
          relationshipService.refsInWorkspace(
              RelationshipType.WORKFLOW, workspace, Optional.of(List.of(workflow.getName())));
      if (!refs.isEmpty()) {
        workflow.setId(refs.get(0));

        // Fill in displayName if not set
        validateAndSetDisplayName(workflow);

        // The same type and template rules create applies, so an update cannot slip past them
        validateTypeForWorkspace(workspace, workflow);
        resolveTemplateRef(workflow);

        // Default Workspace - the same rule create applies, so an update cannot slip past it
        applyWorkspaceDefaults(workspace, workflow);

        // A trigger the request leaves out keeps its stored value, as labels do; then sync the
        // workflow's schedules with the schedule trigger.
        WorkflowTrigger currentTriggers =
            this.get(workspace, workflow.getName(), Optional.empty(), false).getTriggers();
        workflow.setTriggers(withStoredTriggers(workflow.getTriggers(), currentTriggers));
        updateScheduleTriggers(workspace, workflow, currentTriggers);

        // Convert TaskSlugs to Refs(IDs)
        convertTaskSlugsToRefs(workspace, workflow);

        // TODO go through and ensure all the required ParamSpec elements are set

        Workflow appliedWorkflow = apply(workflow, replace).getBody();

        // Filter out sensitive values
        filterSensitiveValues(List.of(appliedWorkflow));

        // Convert Workflow TaskRefs(IDs) to Slugs
        convertTaskRefsToSlugs(workspace, appliedWorkflow);

        workflow.setId(null);
        return appliedWorkflow;
      }
      // The first saved setting of a template makes it the workspace's own: its link is created
      // under the template's name, and the request then applies to the link as to any other.
      Optional<WorkflowEntity> template =
          sharedForWorkspace(workspace, workflow.getName())
              .filter(t -> WorkflowType.template.equals(t.getType()));
      if (template.isPresent()) {
        linkOrAdopt(workspace, template.get());
        workflow.setType(WorkflowType.workflow);
        if (workflow.getTemplateRef() == null) {
          WorkflowTemplateRef ref = new WorkflowTemplateRef();
          ref.setName(template.get().getName());
          workflow.setTemplateRef(ref);
        }
        return apply(workspace, workflow, replace);
      }
    }
    if (workflow != null) {
      workflow.setId(null);
      return this.create(workspace, workflow);
    }
    throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
  }

  /*
   * Submit Workflow to Run as the current caller: a person or a token starting a run (the API, a
   * webhook, an event, Slack) is its initiator, stamped as initiatedByRef.
   */
  public WorkflowRun submit(
      String workspace, String name, WorkflowSubmitRequest request, boolean start) {
    return submit(workspace, name, request, start, taskService.currentActorRef());
  }

  /*
   * Submit Workflow to Run, internally stamping lineage
   *
   * initiatedByRef is NOT sourced from the request - WorkflowSubmitRequest is a public API model
   * and lineage must not be spoofable by API callers. Callers with lineage (ScheduleJob, with the
   * firing Schedule's id) pass it directly; every other caller goes through the 4-arg overload
   * above and is stamped with who it is.
   */
  public WorkflowRun submit(
      String workspace, String name, WorkflowSubmitRequest request, boolean start,
      String initiatedByRef) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)));
    // A template the workspace has not run before runs as the workspace's link to it, created
    // under the template's name now; a starter is copied, never run.
    String workflowRef =
        refs.isEmpty()
            ? linkOrAdopt(workspace, runnableTemplate(workspace, name)).getId()
            : refs.get(0);
    WorkflowRun wfRun = this.internalSubmit(workspace, workflowRef, request, start, initiatedByRef);
    // The submit response is a consumer read like any other: secret-typed params go out
    // redacted. The filter copies the params, so the run the engine holds is untouched.
    DataAdapterUtil.filterWorkflowRunValueByFieldType(wfRun, ParamType.secret);
    return wfRun;
  }

  /*
   * Submit a fresh request to every workflow in the workspace. Each workflow's trigger decides for
   * itself: a workflow that refuses the trigger, or fails to submit, never stops the rest.
   */
  public void internalSubmitForWorkspace(
      String workspace, Supplier<WorkflowSubmitRequest> request, boolean start) {
    for (String workflowId :
        relationshipService.refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.empty())) {
      try {
        internalSubmit(workspace, workflowId, request.get(), start);
      } catch (BoomerangException e) {
        if (!BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED.getReason().equals(e.getReason())) {
          LOGGER.warn("Workflow {} not submitted: {}", workflowId, e.getReason());
        }
      } catch (RuntimeException e) {
        LOGGER.warn("Workflow {} not submitted", workflowId, e);
      }
    }
  }

  /*
   * Submit WorkflowRun Internally
   *
   * Caution: bypasses the authN and authZ and Relationship checks
   */
  public WorkflowRun internalSubmit(
      String workspace, String workflowId, WorkflowSubmitRequest request, boolean start) {
    return internalSubmit(workspace, workflowId, request, start, null);
  }

  /*
   * Submit WorkflowRun Internally, internally stamping lineage - see submit(..., initiatedByRef)
   *
   * Caution: bypasses the authN and authZ and Relationship checks
   */
  public WorkflowRun internalSubmit(
      String workspace, String workflowId, WorkflowSubmitRequest request, boolean start,
      String initiatedByRef) {
    // Check if Workflow exists and is active. Then check triggers are enabled.
    // Presumed workflow exists as relationship was valid to get to this point.
    Workflow workflow = get(workflowId, Optional.empty(), false).getBody();
    // Check Triggers - Throws Exception - Check first, as if trigger not enabled, no point in
    // checking quotas
    canRunWithTrigger(workflow.getTriggers(), request.getTrigger(), request.getParams());
    // Check Quotas - Throws Exception
    quotaService.assertRunQuotas(workspace, Optional.ofNullable(request.getWorkspace()));
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
    // The boomerang.io/ annotation and label prefixes are reserved for the server: any annotation
    // or label a client sent under them is dropped.
    request
        .getAnnotations()
        .keySet()
        .removeIf(key -> key.startsWith(ParamLayerService.SERVER_ANNOTATION_PREFIX));
    request.setLabels(ServerLabel.withoutServerLabels(request.getLabels()));

    // Admit-then-recheck: with quotas enforced the run is created un-started, the quota counts
    // are re-read (now including this run), and a run that pushed a count over its limit is
    // discarded - simultaneous submits cannot each slip past the pre-admission count above.
    boolean enforceQuotas = quotaService.enforced();
    WorkflowRun wfRun = submit(workflowId, request, start && !enforceQuotas, initiatedByRef);
    if (enforceQuotas) {
      try {
        quotaService.assertAdmittedRunWithinQuotas(workspace);
      } catch (BoomerangException overQuota) {
        // The run pushed a count over its limit: it removes itself, so it uses no quota.
        discardRun(wfRun.getId());
        throw overQuota;
      }
    }

    // Creates relationship with owning team
    // TODO: create this run relationship based on decision of team vs workflow
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        workspace,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        wfRun.getId(),
        wfRun.getId(),
        Optional.empty(),
        Optional.empty());
    // A run with a workspace stays ready/pending for the dispatcher to provision and start, and
    // one not admitted (parked for a slot of its workflow, skipped, invalid) is returned as it
    // stands - the same rules WorkflowRunService.run applies on the un-enforced path.
    if (enforceQuotas
        && start
        && wfRun.getWorkspace() == null
        && RunStatus.ready.equals(wfRun.getStatus())) {
      wfRun = workflowRunService.start(wfRun.getId(), Optional.empty());
    }
    return wfRun;
  }

  /*
   * Retrieve a workflows changelog from all versions
   */
  public ResponseEntity<List<ChangeLogVersion>> changelog(String workspace, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    return ResponseEntity.ok(changelog(workflowRef(workspace, name)).getBody());
  }

  /*
   * Change what a workflow is to other workspaces: a workflow, a template or a starter. The
   * revision is untouched. A shared kind is the system workspace's alone, a link can never be
   * one, and a template that other workspaces still link stays a template.
   */
  public Workflow patch(String workspace, String name, WorkflowPatch patch) {
    if (name == null || name.isBlank() || patch == null || patch.getType() == null) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)));
    if (refs.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
    WorkflowEntity entity =
        workflowRepository
            .findById(refs.get(0))
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF));
    WorkflowType type = patch.getType();
    if (type.isSystemOnly() && !SYSTEM_WORKSPACE.equals(workspace)) {
      throw new BoomerangException(BoomerangError.WORKFLOW_TEMPLATE_SYSTEM_ONLY, type.name());
    }
    if (type.isSystemOnly() && entity.getTemplateRef() != null) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
    if (WorkflowType.template.equals(WorkflowType.of(entity.getType()))
        && !WorkflowType.template.equals(type)
        && workflowRepository.existsByTemplateRefRefAndStatusNot(
            entity.getId(), WorkflowStatus.deleted)) {
      throw new BoomerangException(BoomerangError.WORKFLOW_TEMPLATE_IN_USE);
    }
    entity.setType(type);
    workflowRepository.save(entity);
    return get(workspace, name, Optional.empty(), false);
  }

  /*
   * Scoped delete: tombstones the Workflow, then cleans up what only this layer knows about -
   * Schedules, the principal's Tokens, open Actions, and the relationship node. The documents
   * themselves (Workflow, WorkflowRuns, TaskRuns, revisions) are hard-deleted by the retention
   * service's prune, which the watcher runs once in-flight runs finalise.
   */
  public void delete(String workspace, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    List<String> refs =
        relationshipService.refsInWorkspace(
            RelationshipType.WORKFLOW, workspace, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      // A template other workspaces run by reference stays until no live link names it.
      if (workflowRepository.existsByTemplateRefRefAndStatusNot(
          refs.get(0), WorkflowStatus.deleted)) {
        throw new BoomerangException(BoomerangError.WORKFLOW_TEMPLATE_IN_USE);
      }
      // Tombstones the Workflow; the watcher winds down its runs and prunes the documents.
      delete(refs.get(0));
      scheduleService.getObject().deleteAllForWorkflow(refs.get(0));
      tokenService.deleteAllForPrincipal(refs.get(0));
      removeWorkflowReferences(refs.get(0));
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }
  }

  /**
   * Remove what refers to a Workflow outside its own documents: its Actions and its relationship
   * node (by id, which is unique across workspaces). Done when the Workflow is deleted and again
   * when the retention service prunes it; both deletes are idempotent.
   */
  public void removeWorkflowReferences(String workflowId) {
    actionRepository.deleteByWorkflowRef(workflowId);
    relationshipService.removeNodeAndEdgeByRef(RelationshipType.WORKFLOW, workflowId);
  }

  /*
   * Export the Workflow as JSON
   */
  public ResponseEntity<InputStreamResource> export(String workspace, String name) {
    final Workflow workflow = this.get(workspace, name, Optional.empty(), true);

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
  public Workflow duplicate(String workspace, String name) {
    // From the unfiltered read: a copy of the filtered one would carry the redaction marker in
    // place of every secret, and the duplicate would lose them.
    Workflow workflow = this.internalGet(workspace, name, Optional.empty(), true);
    workflow.setName(workflow.getName() + "-duplicate");
    workflow.setDisplayName(workflow.getDisplayName() + " (duplicate)");
    // A template or starter duplicated into a workspace is copied, as a starter is from Home.
    if (SHARED_TYPES.contains(workflow.getType())) {
      workflow.setType(WorkflowType.workflow);
    }
    return this.create(workspace, workflow);
  }

  /*
   * Retrieves Workflow with Tasks and converts / composes it to the appropriate model.
   *
   * Relationship check handled in Get
   *
   * TODO: add a type to handle canvas or Tekton YAML etc etc
   */
  public WorkflowCanvas composeGet(String workspace, String name, Optional<Integer> version) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    // The same masked read as get, keeping the id the canvas carries.
    Workflow response = get(workflowRef(workspace, name), version, true).getBody();
    filterSensitiveValues(List.of(response));
    convertTaskRefsToSlugs(workspace, response);
    return convertWorkflowToCanvas(response);
  }

  /*
   * Retrieves Workflow with Tasks and converts / composes it to the appropriate model.
   *
   * Relationship check handled in Apply
   *
   * TODO: add a type to handle canvas or Tekton YAML etc etc
   */
  public WorkflowCanvas composeApply(String workspace, WorkflowCanvas canvas, boolean replace) {
    if (canvas == null) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    Workflow workflow = convertCanvasToWorkflow(canvas);
    Workflow response = this.apply(workspace, workflow, replace);
    return convertWorkflowToCanvas(response);
  }

  /*
   * Forms the param layers (keys only)
   *
   * Used by the UI to provide helpful prompts on available params
   *
   * Relationship check handled in Get
   */
  public List<String> getAvailableParameters(String workspace, String name) {
    if (name == null || name.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }

    final Workflow workflow = this.get(workspace, name, Optional.empty(), true);
    List<String> paramKeys = paramLayerService.buildParamKeys(workspace, workflow);
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
      final String workspace, final Workflow request, WorkflowTrigger currentTriggers) {
    ScheduleService schedules = scheduleService.getObject();
    if (!Objects.isNull(request.getTriggers())
        && !Objects.isNull(request.getTriggers().getSchedule())
        && !Objects.isNull(currentTriggers)
        && !Objects.isNull(currentTriggers.getSchedule())) {
      boolean currentSchedulerEnabled = currentTriggers.getSchedule().getEnabled();
      boolean requestSchedulerEnabled = request.getTriggers().getSchedule().getEnabled();
      if (currentSchedulerEnabled != false && requestSchedulerEnabled == false) {
        schedules.disableAllTriggerSchedules(workspace, request.getId());
      } else if (currentSchedulerEnabled == false && requestSchedulerEnabled == true) {
        schedules.enableAllTriggerSchedules(workspace, request.getId());
      }
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
          validateEventConditions(params, triggers.getEvent());
          return;
        } else if (TriggerEnum.github.equals(runTrigger) && triggers.getGithub().getEnabled()) {
          validateGitHubConditions(params, triggers.getGithub());
          return;
        }
        throw new BoomerangException(BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED);
      }
    }
  }

  /*
   * Each condition's field is a JSON path into the event as it was sent: its attributes, with its
   * data under "data", so "type" and "data.tag" both read.
   */
  private void validateEventConditions(List<RunParam> params, Trigger trigger) {
    if (trigger.getConditions().isEmpty()) {
      return;
    }
    Map<String, Object> event = new LinkedHashMap<>();
    if (ParameterUtil.getValue(params, "event") instanceof Map<?, ?> attributes) {
      attributes.forEach((name, value) -> event.put(String.valueOf(name), value));
    }
    Object data = ParameterUtil.getValue(params, "data");
    if (data != null) {
      event.put("data", data);
    }
    validateTriggerConditions(trigger, jsonPathValues(event));
  }

  /*
   * The GitHub trigger form filters on two named fields: "events", the event named in GitHub's
   * X-GitHub-Event header, and "repositories", the repository's name. Any other field is a JSON path
   * into the event's payload.
   */
  private void validateGitHubConditions(List<RunParam> params, Trigger trigger) {
    if (trigger.getConditions().isEmpty()) {
      return;
    }
    Function<String, String> payload = jsonPathValues(ParameterUtil.getValue(params, "data"));
    String eventType = jsonPathValues(ParameterUtil.getValue(params, "event")).apply("$.type");
    validateTriggerConditions(
        trigger,
        field ->
            switch (field) {
              case "events" -> eventType;
              case "repositories" -> payload.apply("$.repository.name");
              default -> payload.apply(field);
            });
  }

  /*
   * Every condition must hold. A field with no value - absent, an invalid path, or no event data -
   * matches no condition.
   */
  private void validateTriggerConditions(Trigger trigger, Function<String, String> valueOf) {
    for (TriggerCondition condition : trigger.getConditions()) {
      String value = condition.getField() == null ? null : valueOf.apply(condition.getField());
      boolean holds =
          value != null
              && condition.getOperation() != null
              && switch (condition.getOperation()) {
                case matches -> condition.getValue() != null && value.matches(condition.getValue());
                case equals -> value.equals(condition.getValue());
                case in -> condition.getValues() != null && condition.getValues().contains(value);
              };
      if (!holds) {
        throw new BoomerangException(BoomerangError.WORKFLOWRUN_TRIGGER_DISABLED);
      }
    }
  }

  // Reads a value by JSON path as text; a path that is absent or invalid, or no data, reads null.
  private static Function<String, String> jsonPathValues(Object data) {
    if (data == null) {
      return path -> null;
    }
    DocumentContext document =
        JsonPath.using(JSON_PATH_CONFIGURATION).parse(JSON_PATH_MAPPER.valueToTree(data));
    return path -> {
      try {
        // A leaf reads as a JSON node; unwrapped, text is the bare string rather than quoted JSON.
        Object value = JSON_PATH_CONFIGURATION.jsonProvider().unwrap(document.read(path));
        return value == null ? null : value.toString();
      } catch (JsonPathException | IllegalArgumentException e) {
        return null;
      }
    };
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
          nodeData.setTimeout(task.getTimeout());
          nodeData.setRetry(task.getRetry());
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
          task.setTimeout(node.getData().getTimeout());
          task.setRetry(node.getData().getRetry());

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
   * A finally task is one whose single dependency is end with the finally condition: it runs
   * once the run has ended, any way. The condition belongs to no other dependency, and nothing
   * may depend on a finally task, so finally tasks never chain. Checked at save and again at
   * submit, as the task references are.
   */
  private static void validateFinally(List<WorkflowTask> tasks) {
    Set<String> finallyTasks = new HashSet<>();
    for (WorkflowTask task : tasks) {
      List<WorkflowTaskDependency> dependencies =
          (task.getDependencies() != null) ? task.getDependencies() : List.of();
      if (!DAGUtility.hasFinallyDependency(dependencies)) {
        continue;
      }
      if (dependencies.size() != 1 || !"end".equals(dependencies.get(0).getTaskRef())) {
        throw new BoomerangException(
            BoomerangError.WORKFLOW_INVALID_FINALLY,
            task.getName(),
            "A finally task depends on end alone.");
      }
      finallyTasks.add(task.getName());
    }
    for (WorkflowTask task : tasks) {
      if (task.getDependencies() == null) {
        continue;
      }
      task.getDependencies().stream()
          .map(WorkflowTaskDependency::getTaskRef)
          .filter(finallyTasks::contains)
          .findFirst()
          .ifPresent(
              finallyTask -> {
                throw new BoomerangException(
                    BoomerangError.WORKFLOW_INVALID_FINALLY,
                    task.getName(),
                    "Nothing may depend on the finally task " + finallyTask + ".");
              });
    }
  }

  /*
   * A node's taskRef is the task's id in storage and its slug form on the API; TaskService owns
   * the translation, in one batch per workflow.
   */
  private void convertTaskRefsToSlugs(String workspace, Workflow workflow) {
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
                            // A template's children are the system workspace's, which every
                            // principal may read, so a template's or a link's resolve without
                            // the caller's walk.
                            boolean shared =
                                workflow.getTemplateRef() != null
                                    || SYSTEM_WORKSPACE.equals(workspace)
                                    || SHARED_TYPES.contains(workflow.getType());
                            List<String> slugs =
                                shared
                                    ? ownedWorkflows(SYSTEM_WORKSPACE).stream()
                                        .filter(n -> n.getRef().equals(param.getValue().toString()))
                                        .map(RelationshipNodeEntity::getSlug)
                                        .toList()
                                    : relationshipService.filter(
                                        RelationshipType.WORKFLOW,
                                        Optional.of(List.of(param.getValue().toString())),
                                        Optional.of(RelationshipType.WORKSPACE),
                                        Optional.of(List.of(workspace)));
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

  // A slug form naming another workspace's task, or no task, refuses the save. A link's tasks are
  // the template's, read back in the system workspace's slug forms and dropped at save, so they
  // are not resolved against the link's workspace.
  private void convertTaskSlugsToRefs(String workspace, Workflow workflow) {
    if (workflow.getTemplateRef() != null) {
      return;
    }
    validateTaskRefsPresent(workflow.getTasks());
    validateFinally(workflow.getTasks());
    List<WorkflowTask> nodes =
        workflow.getTasks().stream()
            .filter(t -> !t.getName().equals("start") && !t.getName().equals("end"))
            .toList();
    Map<String, String> taskRefs =
        taskService.refsBySlug(workspace, nodes.stream().map(WorkflowTask::getTaskRef).toList());
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
                                    workspace,
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

    Workflow workflow = modelOf(optWfEntity.get(), optWfRevisionEntity.get());
    if (!withTasks) {
      workflow.setTasks(new LinkedList<>());
    }

    return ResponseEntity.ok(workflow);
  }

  public Page<Workflow> query(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryIds,
      Optional<List<String>> queryTypes,
      Optional<List<String>> queryNames) {
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    Query query =
        new Query(
            new QueryCriteria()
                .labels(queryLabels)
                .in("status", WorkflowStatus.class, queryStatus, "status")
                .in("id", queryIds)
                .in("type", WorkflowType.class, queryTypes, "type")
                .in("name", queryNames)
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
            workflows.add(modelOf(e, optWfRevisionEntity.get()));
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
    wfEntity.setType(request.getType());
    wfEntity.setTemplateRef(request.getTemplateRef());
    // A link's tasks and params are the template's: its own revision holds none.
    if (request.getTemplateRef() != null) {
      request.setTasks(null);
      request.setParams(new LinkedList<>());
    }

    WorkflowRevisionEntity wfRevisionEntity = createWorkflowRevisionEntity(request, 1);
    keepSecretValues(wfRevisionEntity, null);
    wfEntity = workflowRepository.save(wfEntity);
    request.setId(wfEntity.getId());
    wfRevisionEntity.setWorkflowRef(wfEntity.getId());
    workflowRevisionRepository.save(wfRevisionEntity);
    paramLayerCache.evictAll();
    // TODO: figure out a better approach to rollback

    Workflow workflow = modelOf(wfEntity, wfRevisionEntity);
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
    // The webapp sends no author: the change is the caller's.
    if (changelog.getAuthor() == null) {
      changelog.setAuthor(taskService.currentActorRef());
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
    wfRevisionEntity.setConcurrency(request.getConcurrency());
    validateRunSettings(wfRevisionEntity);

    // Check Task Names are unique
    List<String> filteredNames =
        wfRevisionEntity.getTasks().stream().map(t -> t.getName()).collect(Collectors.toList());
    List<String> uniqueFilteredNames =
        filteredNames.stream().distinct().collect(Collectors.toList());
    LOGGER.debug("Name sizes: {} -> {}", filteredNames, uniqueFilteredNames);
    if (filteredNames.size() != uniqueFilteredNames.size()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_NON_UNIQUE_TASK_NAME);
    }
    validateFinally(wfRevisionEntity.getTasks());
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
    if (workflow.getTemplateRef() != null) {
      workflowEntity.setTemplateRef(workflow.getTemplateRef());
    }
    workflowRepository.save(workflowEntity);
    // A link's tasks and params stay the template's, whatever the request carries.
    if (workflowEntity.getTemplateRef() != null) {
      workflow.setTasks(null);
      workflow.setParams(new LinkedList<>());
    }

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

    return ResponseEntity.ok(modelOf(workflowEntity, newWorkflowRevisionEntity));
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

    // A link runs its template's revision, pinned or latest, in its own workspace.
    WorkflowRevisionEntity wfRevision =
        (workflow.getTemplateRef() != null)
            ? templateRevision(workflow.getTemplateRef(), request.getWorkflowVersion())
            : revisionOf(workflowId, request.getWorkflowVersion());
    // A revision upgraded from v3 with workflow-level storage enabled cannot run: that storage no
    // longer exists. Saving the Workflow drops the marker, which is the author's acknowledgement.
    if (Boolean.TRUE.equals(wfRevision.getLegacyWorkflowStorage())) {
      throw new BoomerangException(BoomerangError.WORKFLOW_STORAGE_REMOVED);
    }
    validateTaskRefsPresent(wfRevision.getTasks());
    validateFinally(wfRevision.getTasks());

    final WorkflowRunEntity wfRunEntity = new WorkflowRunEntity();
    wfRunEntity.setWorkflowRevisionRef(wfRevision.getId());
    // The run is the submitted workflow's, which for a link is not the revision's owner.
    wfRunEntity.setWorkflowRef(workflow.getId());
    wfRunEntity.setWorkflowVersion(wfRevision.getVersion());
    wfRunEntity.setCreationDate(new Date());
    wfRunEntity.setStatus(RunStatus.notstarted);
    wfRunEntity.getLabels().putAll(ServerLabel.withoutServerLabels(workflow.getLabels()));
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
            quotaService.maxRunDuration(owningWorkspaceOrNull(workflowId))));
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
            taskService.switchChangeLogAuthorToUserName(cl);
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

  // ── Templates: definitions in the system workspace other workspaces run by reference ──────

  private static final String SYSTEM_WORKSPACE = "system";
  private static final List<WorkflowType> SHARED_TYPES =
      List.of(WorkflowType.template, WorkflowType.starter);

  /**
   * The template or starter of the name the workspace may use: every workspace but system, which
   * owns them, sees the active ones, once the caller reaches the workspace. Empty for a name the
   * shared types do not carry.
   */
  private Optional<WorkflowEntity> sharedForWorkspace(String workspace, String name) {
    if (SYSTEM_WORKSPACE.equals(workspace)
        || !relationshipService.check(
            RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      return Optional.empty();
    }
    return workflowRepository
        .findFirstByNameAndTypeIn(name, SHARED_TYPES)
        .filter(t -> WorkflowStatus.active.equals(t.getStatus()));
  }

  // The template of the name a workspace may run: no workflow of the name is
  // WORKFLOW_INVALID_REF; a starter, copied and never run, is WORKFLOW_TEMPLATE_INVALID_REF.
  private WorkflowEntity runnableTemplate(String workspace, String name) {
    WorkflowEntity shared =
        sharedForWorkspace(workspace, name)
            .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF));
    if (!WorkflowType.template.equals(shared.getType())) {
      throw new BoomerangException(BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF, name, "latest");
    }
    return shared;
  }

  /**
   * The templates and starters a workspace lists beside its own workflows: the active ones whose
   * name no workflow of the workspace carries and (for a template) that none of them links. The
   * system workspace owns them and lists them as its own; a caller who cannot reach the workspace
   * sees none.
   */
  private List<String> availableSharedRefs(String workspace, Optional<List<String>> names) {
    if (SYSTEM_WORKSPACE.equals(workspace)
        || !relationshipService.check(
            RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      return List.of();
    }
    List<RelationshipNodeEntity> owned = ownedWorkflows(workspace);
    Set<String> ownedNames =
        owned.stream().map(RelationshipNodeEntity::getSlug).collect(Collectors.toSet());
    Set<String> linked = new HashSet<>();
    workflowRepository
        .findAllById(owned.stream().map(RelationshipNodeEntity::getRef).toList())
        .forEach(
            w -> {
              if (w.getTemplateRef() != null && !WorkflowStatus.deleted.equals(w.getStatus())) {
                linked.add(w.getTemplateRef().getRef());
              }
            });
    return workflowRepository.findByTypeInAndStatus(SHARED_TYPES, WorkflowStatus.active).stream()
        .filter(t -> names.isEmpty() || names.get().contains(t.getName()))
        .filter(t -> !ownedNames.contains(t.getName()) && !linked.contains(t.getId()))
        .map(WorkflowEntity::getId)
        .toList();
  }

  /**
   * The workflow a runworkflow task's {@code workflowRef} runs as the child of a run of {@code
   * parentWorkflowRef}: a template resolves to the parent's workspace's link to it, adopted on
   * first use, so the child runs where the parent does; in the system workspace, and for any
   * reference that is not a template, the reference is returned unchanged.
   */
  public String resolveChildWorkflowRef(String workflowRef, String parentWorkflowRef) {
    WorkflowEntity named = workflowRepository.findById(workflowRef).orElse(null);
    if (named == null || !WorkflowType.template.equals(named.getType())) {
      return workflowRef;
    }
    String workspace = owningWorkspaceOrNull(parentWorkflowRef);
    if (workspace == null || SYSTEM_WORKSPACE.equals(workspace)) {
      return workflowRef;
    }
    return linkOrAdopt(workspace, named).getId();
  }

  // The workspace's link to the template, or a new one under the template's name. Two first
  // uses at once both pass the name check; the create that loses is refused as a duplicate name
  // and reads the link the other made.
  private WorkflowEntity linkOrAdopt(String workspace, WorkflowEntity template) {
    return existingLink(workspace, template.getId())
        .orElseGet(
            () -> {
              Workflow link = new Workflow();
              link.setName(template.getName());
              link.setDisplayName(template.getDisplayName());
              link.setDescription(template.getDescription());
              link.setIcon(template.getIcon());
              WorkflowTemplateRef ref = new WorkflowTemplateRef();
              ref.setName(template.getName());
              link.setTemplateRef(ref);
              try {
                createOwned(workspace, link);
              } catch (BoomerangException refused) {
                if (!BoomerangError.WORKFLOW_INVALID_REQ.getReason().equals(refused.getReason())) {
                  throw refused;
                }
              }
              // The name is taken by a workflow of the workspace's own: nothing links the template.
              return existingLink(workspace, template.getId())
                  .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ));
            });
  }

  private Optional<WorkflowEntity> existingLink(String workspace, String templateId) {
    List<String> refs = ownedWorkflowRefs(workspace, Optional.empty());
    return refs.isEmpty()
        ? Optional.empty()
        : workflowRepository.findFirstByIdInAndTemplateRefRefAndStatus(
            refs, templateId, WorkflowStatus.active);
  }

  // The workspace's workflows by the workspace's own edges, not the caller's walk, so the engine
  // reads them with no identity; optionally the one of a name.
  private List<String> ownedWorkflowRefs(String workspace, Optional<String> name) {
    return ownedWorkflows(workspace).stream()
        .filter(node -> name.isEmpty() || name.get().equals(node.getSlug()))
        .map(RelationshipNodeEntity::getRef)
        .toList();
  }

  private List<RelationshipNodeEntity> ownedWorkflows(String workspace) {
    return relationshipService.childrenByLabel(
        RelationshipType.WORKSPACE, workspace, RelationshipLabel.HAS_WORKFLOW);
  }

  // A template or a starter is created, applied and imported in the system workspace only; a
  // template cannot itself be a link.
  private static void validateTypeForWorkspace(String workspace, Workflow request) {
    WorkflowType type = WorkflowType.of(request.getType());
    if (type.isSystemOnly() && !SYSTEM_WORKSPACE.equals(workspace)) {
      throw new BoomerangException(BoomerangError.WORKFLOW_TEMPLATE_SYSTEM_ONLY, type.name());
    }
    if (type.isSystemOnly() && request.getTemplateRef() != null) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ);
    }
  }

  // A link names its template by name, optionally pinned to a version; stored, it holds the id.
  private void resolveTemplateRef(Workflow request) {
    WorkflowTemplateRef ref = request.getTemplateRef();
    if (ref == null) {
      return;
    }
    String version = (ref.getVersion() != null) ? String.valueOf(ref.getVersion()) : "latest";
    if (ref.getName() == null || ref.getName().isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF, "", version);
    }
    WorkflowEntity template =
        workflowRepository
            .findFirstByNameAndTypeIn(ref.getName(), List.of(WorkflowType.template))
            .filter(t -> WorkflowStatus.active.equals(t.getStatus()))
            .orElseThrow(
                () ->
                    new BoomerangException(
                        BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF, ref.getName(), version));
    if (ref.getVersion() != null
        && workflowRevisionRepository
            .findByWorkflowRefAndVersion(template.getId(), ref.getVersion())
            .isEmpty()) {
      throw new BoomerangException(
          BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF, ref.getName(), version);
    }
    ref.setRef(template.getId());
  }

  // The revision a link runs or shows: the template's pinned version, or its latest. A submit
  // may name a version of its own, which wins over the pin.
  private WorkflowRevisionEntity templateRevision(WorkflowTemplateRef ref, Integer requested) {
    WorkflowEntity template =
        workflowRepository
            .findById(ref.getRef())
            .filter(t -> WorkflowType.template.equals(t.getType()))
            .filter(t -> WorkflowStatus.active.equals(t.getStatus()))
            .orElseThrow(
                () ->
                    new BoomerangException(
                        BoomerangError.WORKFLOW_TEMPLATE_INVALID_REF,
                        ref.getName(),
                        (ref.getVersion() != null) ? String.valueOf(ref.getVersion()) : "latest"));
    Integer version = (requested != null) ? requested : ref.getVersion();
    return revisionOf(template.getId(), version);
  }

  private WorkflowRevisionEntity revisionOf(String workflowId, Integer version) {
    Optional<WorkflowRevisionEntity> revision =
        (version != null)
            ? workflowRevisionRepository.findByWorkflowRefAndVersion(workflowId, version)
            : workflowRevisionRepository.findByWorkflowRefAndLatestVersion(workflowId);
    return revision.orElseThrow(
        () -> new BoomerangException(BoomerangError.WORKFLOW_REVISION_NOT_FOUND));
  }

  // The public model of a stored workflow: a link shows its template's tasks, params, workspace
  // and run settings, read-only, under its own name, labels and triggers, and names the template.
  private Workflow modelOf(WorkflowEntity entity, WorkflowRevisionEntity revision) {
    Workflow workflow = ConvertUtil.wfEntityToModel(entity, revision);
    workflow.setType(WorkflowType.of(entity.getType()));
    if (entity.getTemplateRef() != null) {
      WorkflowTemplateRef ref = entity.getTemplateRef();
      workflowRepository
          .findById(ref.getRef())
          .ifPresent(template -> ref.setName(template.getName()));
      try {
        WorkflowRevisionEntity shown = templateRevision(ref, null);
        workflow.setTasks(shown.getTasks());
        workflow.setParams(shown.getParams());
        workflow.setWorkspace(shown.getWorkspace());
        workflow.setTimeout(shown.getTimeout());
        workflow.setRetries(shown.getRetries());
        workflow.setConcurrency(shown.getConcurrency());
        workflow.setMarkdown(shown.getMarkdown());
      } catch (BoomerangException gone) {
        // A template deleted under its links: the link reads with no tasks and fails at submit.
        LOGGER.warn("[{}] The template {} no longer resolves.", entity.getId(), ref.getName());
      }
      workflow.setTemplateRef(ref);
    }
    areTaskUpgradesAvailable(workflow);
    return workflow;
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
