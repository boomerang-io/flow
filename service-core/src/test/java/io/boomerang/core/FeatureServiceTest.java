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
 * A flag is on only when its setting is on and the deployment shape leaves a surface behind it,
 * so the webapp renders an embedded engine from the flags alone: one workspace, no workspace or
 * user management, and no sign-in.
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
  void theDefaultsFollowTheSettings() {
    Map<String, Object> features = features(new MockEnvironment());

    assertThat(features)
        .containsEntry("workspace.single", false)
        .containsEntry("workspace.management", true)
        .containsEntry("workspace.quotas", true)
        .containsEntry("user.management", true)
        .containsEntry("insights", true)
        .containsEntry("schedules", true)
        .containsEntry("integrations", true)
        .containsEntry("authentication", true)
        .containsEntry("tokens", true)
        .containsEntry("workflow.tokens", true);
  }

  @Test
  void securityOffHasNoSignInAndNothingForATokenToDo() {
    Map<String, Object> features =
        features(new MockEnvironment().withProperty("flow.security.enabled", "false"));

    assertThat(features)
        .containsEntry("authentication", false)
        .containsEntry("tokens", false)
        .containsEntry("workflow.tokens", false);
  }

  @Test
  void aSingleWorkspaceHasNoWorkspaceOrUserManagementWhateverTheSettings() {
    Map<String, Object> features =
        features(new MockEnvironment().withProperty("flow.workspace.single", "true"));

    assertThat(features)
        .containsEntry("workspace.single", true)
        .containsEntry("workspace.management", false)
        .containsEntry("user.management", false)
        // Everything else follows its setting: the beans behind it always load.
        .containsEntry("workspace.quotas", true)
        .containsEntry("insights", true)
        .containsEntry("schedules", true)
        .containsEntry("integrations", true)
        .containsEntry("activity", true)
        .containsEntry("workspace.parameters", true)
        // Security is still on unless the property says otherwise.
        .containsEntry("authentication", true)
        .containsEntry("tokens", true);
  }

  @Test
  void anEmbeddedEngineIsSingleWorkspaceWithSecurityOff() {
    Map<String, Object> features =
        features(
            new MockEnvironment()
                .withProperty("flow.workspace.single", "true")
                .withProperty("flow.security.enabled", "false"));

    assertThat(features)
        .containsEntry("workspace.single", true)
        .containsEntry("workspace.management", false)
        .containsEntry("user.management", false)
        .containsEntry("authentication", false)
        .containsEntry("tokens", false);
  }
}
