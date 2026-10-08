package io.boomerang.workflow;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.core.SettingsService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
   * The run annotations that carried the global, workspace and context layers before they were
   * read from their stores. Runs created earlier still hold them; reads strip them.
   */
  public static final Set<String> LEGACY_RUN_ANNOTATIONS =
      Set.of(
          "boomerang.io/global-params",
          "boomerang.io/workspace-params",
          "boomerang.io/context-params");

  private SettingsService settingsService;
  private WorkspaceRepository workspaceRepository;
  private ParameterService parameterService;
  private TokenRepository tokenRepository;
  private WorkflowRepository workflowRepository;
  private WorkflowRevisionRepository workflowRevisionRepository;
  private ParamLayerCache paramLayerCache;

  public ParamLayerService(
      SettingsService settingsService,
      WorkspaceRepository workspaceRepository,
      ParameterService parameterService,
      TokenRepository tokenRepository,
      WorkflowRepository workflowRepository,
      WorkflowRevisionRepository workflowRevisionRepository,
      ParamLayerCache paramLayerCache) {
    this.settingsService = settingsService;
    this.workspaceRepository = workspaceRepository;
    this.parameterService = parameterService;
    this.tokenRepository = tokenRepository;
    this.workflowRepository = workflowRepository;
    this.workflowRevisionRepository = workflowRevisionRepository;
    this.paramLayerCache = paramLayerCache;
  }

  /*
   * Used by the /available-params endpoint to retrieve all param keys workflow and above in stack
   */
  public List<String> buildParamKeys(String teamId, Workflow workflow) {
    ParamLayers paramLayers = new ParamLayers();
    Map<String, Object> globalParams = paramLayers.getGlobalParams();
    Map<String, Object> workspaceParams = paramLayers.getWorkspaceParams();
    Map<String, Object> workflowParams = paramLayers.getWorkflowParams();
    Map<String, Object> contextParams = paramLayers.getContextParams();
    List<SettingConfig> features = features();
    // Set Global Params
    if (enabled(features, "globalParameters")) {
      buildGlobalParams(globalParams);
    }
    // Set Workspace Params
    if (enabled(features, "workspaceParameters")) {
      buildWorkspaceParams(workspaceParams, teamId);
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
   * and per-run context keys and resolves. Served from {@link ParamLayerCache}, so the tasks admitted
   * within its TTL share one read of each store.
   */
  public ParamLayers buildParamLayers(
      String workspace, String workflowRef, WorkflowRevisionEntity revision) {
    String revisionId = (revision != null ? revision.getId() : null);
    return paramLayerCache.layers(
        workspace + "/" + workflowRef + "/" + revisionId,
        () ->
            buildParamLayers(
                workspace,
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
      String workspace, WorkflowEntity workflow, WorkflowRevisionEntity revision) {
    ParamLayers paramLayers = new ParamLayers();
    List<SettingConfig> features = features();
    if (enabled(features, "workspaceParameters")) {
      buildWorkspaceParams(paramLayers.getWorkspaceParams(), workspace);
    }
    if (enabled(features, "globalParameters")) {
      buildGlobalParams(paramLayers.getGlobalParams());
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

  /**
   * The values of password-typed global and workspace parameters, which run reads and the log
   * stream redact alongside the password-typed params a workflow or task declares.
   */
  public Set<String> securedValues(String workspace) {
    Set<String> secured = new HashSet<>();
    this.parameterService.getAllUnfiltered().stream()
        .filter(ParamLayerService::isSecured)
        .forEach(param -> secured.add(param.getValue().toString()));
    if (workspace != null) {
      workspaceRepository
          .findByNameIgnoreCase(workspace)
          .map(WorkspaceEntity::getParameters)
          .ifPresent(
              params ->
                  params.stream()
                      .filter(ParamLayerService::isSecured)
                      .forEach(param -> secured.add(param.getValue().toString())));
    }
    return secured;
  }

  // Only values long enough to scrub safely: the log stream replaces every occurrence of each value,
  // so an empty or 1-3 character value would mangle unrelated text (the same floor the run reads
  // apply).
  private static final int MIN_SECURED_VALUE_LENGTH = 4;

  private static boolean isSecured(AbstractParam param) {
    return FieldType.PASSWORD.value().equals(param.getType())
        && param.getValue() != null
        && param.getValue().toString().length() >= MIN_SECURED_VALUE_LENGTH;
  }

  /*
   * Build up global Params layer - defaultValue is not used with Global Params and can be ignored.
   */
  private void buildGlobalParams(Map<String, Object> globalParams) {
    List<AbstractParam> params = this.parameterService.getAllUnfiltered();
    for (AbstractParam param : params) {
      if (param.getValue() != null) {
        globalParams.put(param.getName(), param.getValue());
      }
    }
  }

  /*
   * Build up the Workspace Params - defaultValue is not used with Workspace Params and can be ignored.
   */
  private void buildWorkspaceParams(Map<String, Object> workspaceParams, String team) {
    // A missing workspace contributes no params rather than failing the whole layer build -
    // engine mode resolves every scope to a "default" workspace that has no stored record.
    Optional<WorkspaceEntity> optWorkspaceEntity = workspaceRepository.findByNameIgnoreCase(team);
    if (!optWorkspaceEntity.isPresent()) {
      return;
    }
    WorkspaceEntity workspaceEntity = optWorkspaceEntity.get();
    if (workspaceEntity.getParameters() != null && !workspaceEntity.getParameters().isEmpty()) {
      for (AbstractParam param : workspaceEntity.getParameters()) {
        workspaceParams.put(param.getName(), param.getValue());
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
    contextParams.put("webhook-url", this.settingsService.getWebhookURL());
    contextParams.put("wfe-url", this.settingsService.getWFEURL());
    contextParams.put("event-url", this.settingsService.getEventURL());

    // T6-3: the retired `workflow` token class is now `key` + actorKind=WORKFLOW.
    Optional<List<TokenEntity>> tokens =
        tokenRepository.findByPrincipalAndTypeAndActorKind(
            workflowId, AuthScope.key, TokenActorKind.WORKFLOW);
    // Add Tokens
    if (tokens.isPresent() && !tokens.isEmpty()) {
      for (TokenEntity t : tokens.get()) {
        contextParams.put("tokens." + t.getName(), t.getToken());
      }
    }
  }
}
