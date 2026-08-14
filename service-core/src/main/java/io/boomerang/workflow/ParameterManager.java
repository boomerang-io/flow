package io.boomerang.workflow;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.SettingsService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.workflow.entity.TeamEntity;
import io.boomerang.workflow.repository.TeamRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/*
 * This is one half of the Param Layers. It collects the Global, Team, and Context Layers.
 *
 * The Workflow and Task layers as well as Param resolution will be completed by the Engine
 *
 * CAUTION: this is tightly coupled with Engine
 */
@Service
public class ParameterManager {

  private static final String[] reserved = {"system", "workflow", "global", "team", "workflow"};

  private SettingsService settingsService;
  private TeamRepository teamRepository;
  private ParameterService parameterService;
  private TokenRepository tokenRepository;

  public ParameterManager(
      SettingsService settingsService,
      TeamRepository teamRepository,
      ParameterService parameterService,
      TokenRepository tokenRepository) {
    this.settingsService = settingsService;
    this.teamRepository = teamRepository;
    this.parameterService = parameterService;
    this.tokenRepository = tokenRepository;
  }

  /*
   * Used by the /available-params endpoint to retrieve all param keys workflow and above in stack
   */
  public List<String> buildParamKeys(String teamId, Workflow workflow) {
    ParamLayers paramLayers = new ParamLayers();
    Map<String, Object> globalParams = paramLayers.getGlobalParams();
    Map<String, Object> teamParams = paramLayers.getTeamParams();
    Map<String, Object> workflowParams = paramLayers.getWorkflowParams();
    Map<String, Object> contextParams = paramLayers.getContextParams();
    // Set Global Params
    if (settingsService.getSettingConfig("features", "globalParameters").getBooleanValue()) {
      buildGlobalParams(globalParams);
    }
    // Set Team Params
    if (settingsService.getSettingConfig("features", "teamParameters").getBooleanValue()) {
      buildTeamParams(teamParams, teamId);
    }
    // Set the Keys from the Workflow - ignore values
    for (AbstractParam wfParam : workflow.getParams()) {
      workflowParams.put(wfParam.getName(), "");
    }
    buildContextParams(contextParams, workflow);

    return paramLayers.getFlatKeys();
  }

  /*
   * Only needs to set the Global, Team, and partial Context Params. Engine will add and resolve.
   */
  public ParamLayers buildParamLayers(String teamId, Workflow workflow) {
    ParamLayers paramLayers = new ParamLayers();
    Map<String, Object> teamParams = paramLayers.getTeamParams();
    Map<String, Object> globalParams = paramLayers.getGlobalParams();
    Map<String, Object> contextParams = paramLayers.getContextParams();
    // Set Team Params
    if (settingsService.getSettingConfig("features", "teamParameters").getBooleanValue()) {
      buildTeamParams(teamParams, teamId);
    }
    // Set Global Params
    if (settingsService.getSettingConfig("features", "globalParameters").getBooleanValue()) {
      buildGlobalParams(globalParams);
    }
    buildContextParams(contextParams, workflow);

    return paramLayers;
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
   * Build up the Team Params - defaultValue is not used with Team Params and can be ignored.
   */
  private void buildTeamParams(Map<String, Object> teamParams, String team) {
    Optional<TeamEntity> optTeamEntity = teamRepository.findByNameIgnoreCase(team);
    if (!optTeamEntity.isPresent()) {
      throw new BoomerangException(BoomerangError.TEAM_INVALID_REF);
    }
    TeamEntity teamEntity = optTeamEntity.get();
    if (teamEntity.getParameters() != null && !teamEntity.getParameters().isEmpty()) {
      for (AbstractParam param : teamEntity.getParameters()) {
        teamParams.put(param.getName(), param.getValue());
      }
    }
  }

  /*
   * Build up the reserved system Params
   *
   * TODO: check this with the reserved Tekton ones
   */
  private void buildContextParams(Map<String, Object> contextParams, Workflow workflow) {
    contextParams.put("workflowrun-trigger", "");
    contextParams.put("workflowrun-initiator", "");
    contextParams.put("workflowrun-ref", "");
    contextParams.put("workflow-name", workflow.getName());
    contextParams.put("workflow-displayname", workflow.getDisplayName());
    contextParams.put("workflow-ref", workflow.getId());
    contextParams.put("workflow-version", workflow.getVersion());
    contextParams.put("taskrun-ref", "");
    contextParams.put("taskrun-name", "");
    contextParams.put("taskrun-type", "");
    contextParams.put("webhook-url", this.settingsService.getWebhookURL());
    contextParams.put("wfe-url", this.settingsService.getWFEURL());
    contextParams.put("event-url", this.settingsService.getEventURL());

    Optional<List<TokenEntity>> tokens =
        tokenRepository.findByPrincipalAndType(workflow.getId(), AuthScope.workflow);
    // Add Tokens
    if (tokens.isPresent() && !tokens.isEmpty()) {
      for (TokenEntity t : tokens.get()) {
        contextParams.put("tokens." + t.getName(), t.getToken());
      }
    }
  }
}
