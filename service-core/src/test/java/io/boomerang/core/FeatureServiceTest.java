package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.core.model.SettingConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A flag is on only when its setting is on and the surface behind it is loaded, so the webapp
 * renders engine mode from the flags alone: one workspace, and no workspace management, quotas,
 * users, insights, schedules, integrations or sign-in.
 */
class FeatureServiceTest {

  private final SettingsService settingsService = mock(SettingsService.class);

  @BeforeEach
  void everySettingOn() {
    SettingConfig on = new SettingConfig();
    on.setType("boolean");
    on.setValue("true");
    when(settingsService.getSettingConfig(anyString(), anyString())).thenReturn(on);
  }

  private Map<String, Object> features(MockEnvironment environment) {
    return new FeatureService(settingsService, environment).get().getFeatures();
  }

  @Test
  void standaloneFollowsTheSettings() {
    Map<String, Object> features = features(new MockEnvironment());

    assertThat(features)
        .containsEntry("workspace.single", false)
        .containsEntry("workspace.management", true)
        .containsEntry("workspace.quotas", true)
        .containsEntry("user.management", true)
        .containsEntry("insights", true)
        .containsEntry("schedules", true)
        .containsEntry("integrations", true)
        .containsEntry("authentication", true);
  }

  @Test
  void standaloneWithSecurityOffHasNoSignIn() {
    Map<String, Object> features =
        features(new MockEnvironment().withProperty("flow.security.enabled", "false"));

    assertThat(features).containsEntry("authentication", false);
  }

  @Test
  void engineModeTurnsOffWhatItDoesNotLoadWhateverTheSettings() {
    Map<String, Object> features =
        features(new MockEnvironment().withProperty("flow.mode", "engine"));

    assertThat(features)
        .containsEntry("workspace.single", true)
        .containsEntry("workspace.management", false)
        .containsEntry("workspace.quotas", false)
        .containsEntry("user.management", false)
        .containsEntry("insights", false)
        .containsEntry("schedules", false)
        .containsEntry("integrations", false)
        .containsEntry("authentication", false)
        .containsEntry("activity", true)
        .containsEntry("workspace.parameters", true);
  }

  @Test
  void engineModeWithSecurityOnStillHasNoSignInSurface() {
    Map<String, Object> features =
        features(
            new MockEnvironment()
                .withProperty("flow.mode", "engine")
                .withProperty("flow.security.enabled", "true"));

    assertThat(features).containsEntry("authentication", false);
  }
}
