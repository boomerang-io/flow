package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.core.SettingsService;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParamLayerServiceTest {

  private final SettingsService settingsService = mock(SettingsService.class);
  private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
  private final ParameterService parameterService = mock(ParameterService.class);
  private final TokenRepository tokenRepository = mock(TokenRepository.class);
  private final ParamLayerService service =
      new ParamLayerService(settingsService, workspaceRepository, parameterService, tokenRepository);

  @BeforeEach
  void setUp() {
    SettingConfig on = new SettingConfig();
    on.setType("boolean");
    on.setValue("true");
    when(settingsService.getSettingConfig(eq("features"), anyString())).thenReturn(on);
    when(tokenRepository.findByPrincipalAndTypeAndActorKind(any(), any(), any()))
        .thenReturn(Optional.empty());
  }

  private static AbstractParam param(String name, String type, String value) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    param.setValue(value);
    return param;
  }

  private void workspace(String name, AbstractParam... params) {
    WorkspaceEntity workspace = new WorkspaceEntity();
    workspace.setName(name);
    workspace.setParameters(List.of(params));
    when(workspaceRepository.findByNameIgnoreCase(name)).thenReturn(Optional.of(workspace));
  }

  // Secured values are scrubbed everywhere they occur - the log stream replaces every occurrence -
  // so only values of four characters or more are returned, never an empty or short one.
  @Test
  void securedValuesAreThePasswordTypedOnesLongEnoughToScrub() {
    when(parameterService.getAllUnfiltered())
        .thenReturn(
            List.of(
                param("registryToken", "password", "glob-secret-1"),
                param("empty", "password", ""),
                param("short", "password", "abc"),
                param("registry", "text", "ghcr.io")));
    workspace("cheer", param("githubToken", "password", "ws-secret-22"), param("branch", "text", "main"));

    assertThat(service.securedValues("cheer")).containsExactlyInAnyOrder("glob-secret-1", "ws-secret-22");
    assertThat(service.securedValues(null)).containsExactly("glob-secret-1");
  }

  // The layers are read for the run's workspace; the context's version is the revision the run
  // runs, not the workflow's latest.
  @Test
  void buildsTheLayersForAWorkspaceWithContextFromTheRevision() {
    when(parameterService.getAllUnfiltered()).thenReturn(List.of(param("g1", "text", "GV")));
    workspace("cheer", param("w1", "text", "WV"));
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setId("wf-1");
    workflow.setName("version-analysis");
    workflow.setDisplayName("Version analysis");
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setVersion(3);

    ParamLayers layers = service.buildParamLayers("cheer", workflow, revision);

    assertThat(layers.getGlobalParams()).containsEntry("g1", "GV");
    assertThat(layers.getWorkspaceParams()).containsEntry("w1", "WV");
    assertThat(layers.getContextParams())
        .containsEntry("workflow-ref", "wf-1")
        .containsEntry("workflow-name", "version-analysis")
        .containsEntry("workflow-displayname", "Version analysis")
        .containsEntry("workflow-version", 3);
  }
}
