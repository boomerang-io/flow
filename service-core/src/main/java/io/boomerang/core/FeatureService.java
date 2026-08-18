package io.boomerang.core;

import io.boomerang.core.model.Features;
import io.boomerang.core.model.SettingConfig;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class FeatureService {

  private static final String VERIFIED_TASK_EDIT_KEY = "enable.verified.tasks.edit";

  private final SettingsService settingsService;

  public FeatureService(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  public Features get() {
    Features flowFeatures = new Features();
    Map<String, Object> features = new HashMap<>();

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
    features.put(
        "workflow.tokens",
        settingsService.getSettingConfig("features", "workflowTokens").getBooleanValue());
    features.put(
        "workspace.parameters",
        settingsService.getSettingConfig("features", "workspaceParameters").getBooleanValue());
    features.put(
        "global.parameters",
        settingsService.getSettingConfig("features", "globalParameters").getBooleanValue());
    features.put(
        "workspace.management",
        settingsService.getSettingConfig("features", "workspaceManagement").getBooleanValue());
    features.put(
        "user.management",
        settingsService.getSettingConfig("features", "userManagement").getBooleanValue());
    features.put(
        "activity", settingsService.getSettingConfig("features", "activity").getBooleanValue());
    features.put(
        "insights", settingsService.getSettingConfig("features", "insights").getBooleanValue());
    features.put(
        "workspace.tasks",
        settingsService.getSettingConfig("features", "workspaceTasks").getBooleanValue());

    flowFeatures.setFeatures(features);
    return flowFeatures;
  }
}
