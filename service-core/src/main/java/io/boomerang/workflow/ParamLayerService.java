package io.boomerang.workflow;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.core.SettingsService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.TokenActorKind;
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

  public ParamLayerService(
      SettingsService settingsService,
      WorkspaceRepository workspaceRepository,
      ParameterService parameterService,
      TokenRepository tokenRepository) {
    this.settingsService = settingsService;
    this.workspaceRepository = workspaceRepository;
    this.parameterService = parameterService;
    this.tokenRepository = tokenRepository;
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
    // Set Global Params
    if (settingsService.getSettingConfig("features", "globalParameters").getBooleanValue()) {
      buildGlobalParams(globalParams);
    }
    // Set Workspace Params
    if (settingsService.getSettingConfig("features", "workspaceParameters").getBooleanValue()) {
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
   * The global, workspace and context layers for a run, read from their stores now - nothing is
   * copied onto the run. The context's workflow name, ref and version come from the run's workflow
   * and the revision it runs, so a version saved mid-run does not change them. The engine adds the
   * run, task and per-run context keys and resolves.
   */
  public ParamLayers buildParamLayers(
      String workspace, WorkflowEntity workflow, WorkflowRevisionEntity revision) {
    ParamLayers paramLayers = new ParamLayers();
    if (settingsService.getSettingConfig("features", "workspaceParameters").getBooleanValue()) {
      buildWorkspaceParams(paramLayers.getWorkspaceParams(), workspace);
    }
    if (settingsService.getSettingConfig("features", "globalParameters").getBooleanValue()) {
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

  private static boolean isSecured(AbstractParam param) {
    return FieldType.PASSWORD.value().equals(param.getType()) && param.getValue() != null;
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
