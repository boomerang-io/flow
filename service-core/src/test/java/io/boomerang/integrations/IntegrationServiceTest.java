package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.security.IdentityService;
import io.boomerang.integrations.entity.IntegrationTemplateEntity;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.Integration;
import io.boomerang.integrations.repository.IntegrationTemplateRepository;
import io.boomerang.integrations.repository.IntegrationsRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IntegrationServiceTest {

  private final IntegrationTemplateRepository templates = mock(IntegrationTemplateRepository.class);
  private final IntegrationsRepository integrations = mock(IntegrationsRepository.class);
  private final RelationshipService relationships = mock(RelationshipService.class);
  private final SettingsService settings = mock(SettingsService.class);

  private final IntegrationService service =
      new IntegrationService(
          templates,
          integrations,
          relationships,
          settings,
          mock(GitHubService.class),
          mock(IdentityService.class));

  private static IntegrationTemplateEntity template(String name, String type) {
    IntegrationTemplateEntity template = new IntegrationTemplateEntity();
    template.setName(name);
    template.setType(type);
    template.setLink("https://github.com/apps/{app_name}/installations/select_target");
    template.setStatus("active");
    return template;
  }

  @Test
  void linksEachIntegrationByTheRefOfItsOwnType() {
    when(templates.findAllByStatus("active"))
        .thenReturn(List.of(template("GitHub", "github_app"), template("Slack", "slack")));
    when(relationships.filter(
            any(RelationshipType.class), any(), any(), any(), any(Boolean.class)))
        .thenReturn(List.of("slack-ref", "github-ref"));
    when(integrations.findByIdAndType(anyString(), anyString())).thenReturn(Optional.empty());
    when(integrations.findByIdAndType("github-ref", "github_app"))
        .thenReturn(Optional.of(new IntegrationsEntity()));
    when(integrations.findByIdAndType("slack-ref", "slack"))
        .thenReturn(Optional.of(new IntegrationsEntity()));
    SettingConfig appName = new SettingConfig();
    appName.setValue("flowabl-io");
    when(settings.getSettingConfig("integration", "github.appName")).thenReturn(appName);

    Map<String, Integration> byName =
        service.get("cheer").stream()
            .collect(Collectors.toMap(Integration::getName, Function.identity()));

    // Before, both took the workspace's first ref, so GitHub (checked against slack-ref) read as unlinked
    // and disconnecting Slack or GitHub sent the same ref.
    assertThat(byName.get("GitHub").getStatus()).isEqualTo(IntegrationStatus.linked);
    assertThat(byName.get("GitHub").getRef()).isEqualTo("github-ref");
    assertThat(byName.get("Slack").getStatus()).isEqualTo(IntegrationStatus.linked);
    assertThat(byName.get("Slack").getRef()).isEqualTo("slack-ref");
  }

  @Test
  void leavesAnIntegrationWithNoRefOfItsTypeUnlinked() {
    when(templates.findAllByStatus("active")).thenReturn(List.of(template("Slack", "slack")));
    when(relationships.filter(
            any(RelationshipType.class), any(), any(), any(), any(Boolean.class)))
        .thenReturn(List.of("github-ref"));
    when(integrations.findByIdAndType(anyString(), anyString())).thenReturn(Optional.empty());

    Integration slack = service.get("cheer").get(0);

    assertThat(slack.getStatus()).isEqualTo(IntegrationStatus.unlinked);
    assertThat(slack.getRef()).isNull();
  }
}
