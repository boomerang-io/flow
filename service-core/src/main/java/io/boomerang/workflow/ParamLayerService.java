package io.boomerang.workflow;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
 * This is one half of the Param Layers. It collects the Global, Workspace, and Context Layers.
 *
 * The Workflow and Task layers as well as Param resolution will be completed by the Engine
 *
 * CAUTION: this is tightly coupled with Engine
 */
@Service
public class ParamLayerService {

  private static final String[] reserved = {"system", "workflow", "global", "team", "workflow"};

  /**
   * The run annotation prefix reserved for the server: a client's submit or start never sets one.
   * The engine decides from typed fields and the relationship graph, never from an annotation.
   */
  public static final String SERVER_ANNOTATION_PREFIX = "boomerang.io/";

  // The inbound routes a task can call back on, handed to every run as context params.
  @Value("${flow.workflow.webhook.url}")
  private String webhookUrl;

  @Value("${flow.workflow.wfe.url}")
  private String waitForEventUrl;

  @Value("${flow.workflow.event.url}")
  private String eventUrl;

  private SettingsService settingsService;
  private WorkspaceRepository workspaceRepository;
  private ParameterService parameterService;
  private WorkflowRepository workflowRepository;
  private WorkflowRevisionRepository workflowRevisionRepository;
  private ParamLayerCache paramLayerCache;
  private RelationshipService relationshipService;

  public ParamLayerService(
      SettingsService settingsService,
      WorkspaceRepository workspaceRepository,
      ParameterService parameterService,
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      ParamLayerCache paramLayerCache,
      RelationshipService relationshipService) {
    this.settingsService = settingsService;
    this.workspaceRepository = workspaceRepository;
    this.parameterService = parameterService;
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.paramLayerCache = paramLayerCache;
    this.relationshipService = relationshipService;
  }

  /**
   * The id of the workspace that owns the workflow - the workspace a run of it belongs to - or null
   * when the relationship graph records none. Held by id, so a renamed workspace is still found.
   */
  public String owningWorkspace(String workflowRef) {
    if (workflowRef == null) {
      return null;
    }
    return paramLayerCache.owner(
        workflowRef,
        ref -> {
          String owner =
              relationshipService.getParentByLabel(
                  RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, ref);
          return (owner != null && !owner.isBlank()) ? owner : null;
        });
  }

  /*
   * Used by the /available-params endpoint to retrieve all param keys workflow and above in stack
   */
  public List<String> buildParamKeys(String workspace, Workflow workflow) {
    ParamLayers paramLayers = new ParamLayers();
    Map<String, Object> globalParams = paramLayers.getGlobalParams();
    Map<String, Object> workspaceParams = paramLayers.getWorkspaceParams();
    Map<String, Object> workflowParams = paramLayers.getWorkflowParams();
    Map<String, Object> contextParams = paramLayers.getContextParams();
    List<SettingConfig> features = features();
    // Set Global Params
    if (enabled(features, "globalParameters")) {
      buildGlobalParams(globalParams, new HashSet<>());
    }
    // Set Workspace Params
    if (enabled(features, "workspaceParameters")) {
      buildWorkspaceParams(
          workspaceParams, new HashSet<>(), workspaceRepository.findByNameIgnoreCase(workspace));
    }
    // Set the Keys from the Workflow - ignore values
    for (AbstractParam wfParam : workflow.getParams()) {
      workflowParams.put(wfParam.getName(), "");
    }
    buildContextParams(contextParams, workflow);

    return paramLayers.getFlatKeys();
  }

  /**
   * The global, workspace and context layers for a run, read from their stores - nothing is copied
   * onto the run. The context's workflow name, ref and version come from the run's workflow and the
   * revision it runs, so a version saved mid-run does not change them. The engine adds the run, task
   * and per-run context keys and resolves. The workspace layer is the workflow's owner's. Served
   * from {@link ParamLayerCache}, so the tasks admitted within its TTL share one read of each store.
   */
  public ParamLayers buildParamLayers(String workflowRef, WorkflowRevisionEntity revision) {
    String revisionId = (revision != null ? revision.getId() : null);
    return paramLayerCache.layers(
        workflowRef + "/" + revisionId,
        () ->
            buildParamLayers(
                owningWorkspace(workflowRef),
                (workflowRef != null ? workflowRepository.findById(workflowRef).orElse(null) : null),
                revision));
  }

  /** The revision with this id, or null when it does not exist; served from {@link ParamLayerCache}. */
  public WorkflowRevisionEntity getRevision(String id) {
    return (id != null
        ? paramLayerCache.revision(id, key -> workflowRevisionRepository.findById(key).orElse(null))
        : null);
  }

  ParamLayers buildParamLayers(
      String workspaceId, WorkflowEntity workflow, WorkflowRevisionEntity revision) {
    ParamLayers paramLayers = new ParamLayers();
    List<SettingConfig> features = features();
    if (enabled(features, "workspaceParameters")) {
      buildWorkspaceParams(
          paramLayers.getWorkspaceParams(),
          paramLayers.getSecretWorkspaceParams(),
          workspace(workspaceId));
    }
    if (enabled(features, "globalParameters")) {
      buildGlobalParams(paramLayers.getGlobalParams(), paramLayers.getSecretGlobalParams());
    }
    if (workflow != null) {
      buildContextParams(
          paramLayers.getContextParams(),
          workflow.getId(),
          workflow.getName(),
          workflow.getDisplayName(),
          revision != null ? revision.getVersion() : null);
    }
    return paramLayers;
  }

  // The feature switches, read in one go: both parameter switches live in the one settings document.
  private List<SettingConfig> features() {
    return settingsService.getSettingByKey("features").getConfig();
  }

  private static boolean enabled(List<SettingConfig> features, String name) {
    return features.stream().anyMatch(config -> name.equals(config.getKey()) && config.getBooleanValue());
  }

  // The workspace with this id; empty when there is none, or no id.
  private Optional<WorkspaceEntity> workspace(String workspaceId) {
    return (workspaceId != null ? workspaceRepository.findById(workspaceId) : Optional.empty());
  }

  /**
   * The values of password-typed global and workspace parameters, which run reads and the log
   * stream redact alongside the password-typed params a workflow or task declares. The workspace
   * is the workflow's owner.
   */
  public Set<String> securedValues(String workflowRef) {
    Set<String> secured = new HashSet<>();
    this.parameterService.getAllUnfiltered().stream()
        .filter(ParamLayerService::isSecured)
        .forEach(param -> secured.add(param.getValue().toString()));
    workspace(owningWorkspace(workflowRef))
        .map(WorkspaceEntity::getParameters)
        .ifPresent(
            params ->
                params.stream()
                    .filter(ParamLayerService::isSecured)
                    .forEach(param -> secured.add(param.getValue().toString())));
    return secured;
  }

  // Only values long enough to scrub safely: the log stream replaces every occurrence of each value,
  // so an empty or 1-3 character value would mangle unrelated text (the same floor the run reads
  // apply).
  private static final int MIN_SECURED_VALUE_LENGTH = 4;

  private static boolean isPassword(AbstractParam param) {
    return FieldType.PASSWORD.value().equals(param.getType());
  }

  private static boolean isSecured(AbstractParam param) {
    return FieldType.PASSWORD.value().equals(param.getType())
        && param.getValue() != null
        && param.getValue().toString().length() >= MIN_SECURED_VALUE_LENGTH;
  }

  /*
   * Build up global Params layer - defaultValue is not used with Global Params and can be ignored.
   */
  private void buildGlobalParams(Map<String, Object> globalParams, Set<String> secretNames) {
    List<AbstractParam> params = this.parameterService.getAllUnfiltered();
    for (AbstractParam param : params) {
      if (param.getValue() != null) {
        globalParams.put(param.getName(), param.getValue());
        if (isPassword(param)) {
          secretNames.add(param.getName());
        }
      }
    }
  }

  /*
   * Build up the Workspace Params - defaultValue is not used with Workspace Params and can be ignored.
   */
  private void buildWorkspaceParams(
      Map<String, Object> workspaceParams,
      Set<String> secretNames,
      Optional<WorkspaceEntity> optWorkspaceEntity) {
    // A missing workspace contributes no params rather than failing the whole layer build.
    if (optWorkspaceEntity.isEmpty()) {
      return;
    }
    WorkspaceEntity workspaceEntity = optWorkspaceEntity.get();
    if (workspaceEntity.getParameters() != null && !workspaceEntity.getParameters().isEmpty()) {
      for (AbstractParam param : workspaceEntity.getParameters()) {
        workspaceParams.put(param.getName(), param.getValue());
        if (isPassword(param)) {
          secretNames.add(param.getName());
        }
      }
    }
  }

  /*
   * Build up the reserved system Params
   *
   * TODO: check this with the reserved Tekton ones
   */
  private void buildContextParams(Map<String, Object> contextParams, Workflow workflow) {
    buildContextParams(
        contextParams,
        workflow.getId(),
        workflow.getName(),
        workflow.getDisplayName(),
        workflow.getVersion());
  }

  private void buildContextParams(
      Map<String, Object> contextParams,
      String workflowId,
      String workflowName,
      String workflowDisplayName,
      Integer workflowVersion) {
    contextParams.put("workflowrun-trigger", "");
    contextParams.put("workflowrun-initiator", "");
    contextParams.put("workflowrun-ref", "");
    contextParams.put("workflow-name", workflowName);
    contextParams.put("workflow-displayname", workflowDisplayName);
    contextParams.put("workflow-ref", workflowId);
    contextParams.put("workflow-version", workflowVersion);
    contextParams.put("taskrun-ref", "");
    contextParams.put("taskrun-name", "");
    contextParams.put("taskrun-type", "");
    contextParams.put("webhook-url", webhookUrl);
    contextParams.put("wfe-url", waitForEventUrl);
    contextParams.put("event-url", eventUrl);
  }
}
