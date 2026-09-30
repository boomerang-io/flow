package io.boomerang.core;

import io.boomerang.config.FlowMode;
import io.boomerang.core.model.Features;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.security.FlowSecurityProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/*
 * The flags the webapp renders from. A flag is on only when its setting is on AND the surface
 * behind it is loaded in this mode, so the webapp never needs to know which mode it runs against:
 * engine mode serves one workspace and no workspace management, quotas, users, insights, schedules,
 * integrations or sign-in surface, and its flags say exactly that.
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
    boolean standalone = FlowMode.resolve(environment) == FlowMode.STANDALONE;

    SettingConfig config = settingsService.getSettingConfig("task", "edit.verified");

    if (config != null) {
      features.put(VERIFIED_TASK_EDIT_KEY, config.getBooleanValue());
    } else {
      features.put(VERIFIED_TASK_EDIT_KEY, false);
    }
    features.put(
        "workspace.quotas",
        standalone
            && settingsService.getSettingConfig("features", "workspaceQuotas").getBooleanValue());
    features.put(
        "workflow.triggers",
        settingsService.getSettingConfig("features", "workflowTriggers").getBooleanValue());
    // Tokens authenticate API callers; with security off every request runs as the synthetic admin,
    // so there is nothing for a token to do and the token pages and workflow tokens are hidden.
    boolean security = FlowSecurityProperties.isSecurityEnabled(environment);
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
        standalone
            && settingsService.getSettingConfig("features", "workspaceManagement").getBooleanValue());
    features.put(
        "user.management",
        standalone
            && settingsService.getSettingConfig("features", "userManagement").getBooleanValue());
    features.put(
        "activity", settingsService.getSettingConfig("features", "activity").getBooleanValue());
    features.put(
        "insights", settingsService.getSettingConfig("features", "insights").getBooleanValue());
    features.put(
        "workspace.tasks",
        settingsService.getSettingConfig("features", "workspaceTasks").getBooleanValue());
    features.put("workspace.single", !standalone);
    features.put("schedules", true);
    features.put("integrations", standalone);
    // The sign-in surface (GET /api/v2/auth/config and the session exchange) exists only here.
    features.put(
        "authentication", standalone && security);

    flowFeatures.setFeatures(features);
    return flowFeatures;
  }
}
