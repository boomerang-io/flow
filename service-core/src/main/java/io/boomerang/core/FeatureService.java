package io.boomerang.core;

import io.boomerang.core.model.Features;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.security.FlowSecurityProperties;
import io.boomerang.core.security.FlowWorkspaceProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/*
 * The flags the webapp renders from. A flag is on only when its setting is on AND the deployment
 * shape (flow.security.enabled, flow.workspace.single) leaves a surface behind it, so the webapp
 * never reads a property: a single-workspace instance has one workspace and no workspace or user
 * management, and a security-off instance has no sign-in surface and nothing for a token to do.
 */
@Service
public class FeatureService {

  private static final String VERIFIED_TASK_EDIT_KEY = "enable.verified.tasks.edit";

  private final SettingsService settingsService;
  private final Environment environment;

  public FeatureService(SettingsService settingsService, Environment environment) {
    this.settingsService = settingsService;
    this.environment = environment;
  }

  public Features get() {
    Features flowFeatures = new Features();
    Map<String, Object> features = new HashMap<>();
    boolean single = FlowWorkspaceProperties.isSingle(environment);
    boolean security = FlowSecurityProperties.isSecurityEnabled(environment);

    SettingConfig config = settingsService.getSettingConfig("task", "edit.verified");

    if (config != null) {
      features.put(VERIFIED_TASK_EDIT_KEY, config.getBooleanValue());
    } else {
      features.put(VERIFIED_TASK_EDIT_KEY, false);
    }
    features.put(
        "workspace.quotas",
        settingsService.getSettingConfig("features", "workspaceQuotas").getBooleanValue());
    features.put(
        "workflow.triggers",
        settingsService.getSettingConfig("features", "workflowTriggers").getBooleanValue());
    // Tokens authenticate API callers; with security off every request runs as the synthetic admin,
    // so there is nothing for a token to do and the token pages and workflow tokens are hidden.
    features.put("tokens", security);
    features.put(
        "workflow.tokens",
        security
            && settingsService.getSettingConfig("features", "workflowTokens").getBooleanValue());
    features.put(
        "workspace.parameters",
        settingsService.getSettingConfig("features", "workspaceParameters").getBooleanValue());
    features.put(
        "global.parameters",
        settingsService.getSettingConfig("features", "globalParameters").getBooleanValue());
    features.put(
        "workspace.management",
        !single
            && settingsService.getSettingConfig("features", "workspaceManagement").getBooleanValue());
    features.put(
        "user.management",
        !single
            && settingsService.getSettingConfig("features", "userManagement").getBooleanValue());
    features.put("workspace.create", !single && settingsService.workspaceCreationAllowed());
    features.put(
        "activity", settingsService.getSettingConfig("features", "activity").getBooleanValue());
    features.put(
        "insights", settingsService.getSettingConfig("features", "insights").getBooleanValue());
    features.put(
        "workspace.tasks",
        settingsService.getSettingConfig("features", "workspaceTasks").getBooleanValue());
    features.put("workspace.single", single);
    features.put("schedules", true);
    features.put("integrations", true);
    // The sign-in surface (GET /internal/v1/auth/config and the session exchange) needs security on.
    features.put("authentication", security);

    flowFeatures.setFeatures(features);
    return flowFeatures;
  }
}
