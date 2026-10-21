package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.core.ParamLayerCache;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.SettingsService;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParamLayerServiceTest {

  private final SettingsService settingsService = mock(SettingsService.class);
  private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
  private final ParameterService parameterService = mock(ParameterService.class);
  private final TokenRepository tokenRepository = mock(TokenRepository.class);
  private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
  private final WorkflowRevisionRepository workflowRevisionRepository =
      mock(WorkflowRevisionRepository.class);
  private final RelationshipService relationshipService = mock(RelationshipService.class);
  private final ParamLayerService service = service(Duration.ofSeconds(10));

  private ParamLayerService service(Duration ttl) {
    return new ParamLayerService(
        settingsService,
        workspaceRepository,
        parameterService,
        tokenRepository,
        workflowRepository,
        workflowRevisionRepository,
        new ParamLayerCache(true, ttl, 1000),
        relationshipService);
  }

  private static SettingConfig on(String key) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setType("boolean");
    config.setValue("true");
    return config;
  }

  @BeforeEach
  void setUp() {
    SettingEntity features = new SettingEntity();
    features.setConfig(List.of(on("globalParameters"), on("workspaceParameters")));
    when(settingsService.getSettingByKey("features")).thenReturn(features);
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

  private void workspace(String id, AbstractParam... params) {
    WorkspaceEntity workspace = new WorkspaceEntity();
    workspace.setId(id);
    workspace.setName(id + "-name");
    workspace.setParameters(List.of(params));
    when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspace));
  }

  // The relationship graph records the workspace as the workflow's owner, by the workspace's id.
  private void owns(String workspaceId, String workflowRef) {
    when(relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef))
        .thenReturn(workspaceId);
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
    workspace("ws-cheer", param("githubToken", "password", "ws-secret-22"), param("branch", "text", "main"));
    owns("ws-cheer", "wf-1");
    when(relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, "wf-unowned"))
        .thenReturn("");

    assertThat(service.securedValues("wf-1")).containsExactlyInAnyOrder("glob-secret-1", "ws-secret-22");
    assertThat(service.securedValues("wf-unowned")).containsExactly("glob-secret-1");
    assertThat(service.securedValues(null)).containsExactly("glob-secret-1");
  }

  // The layers are read for the run's workspace; the context's version is the revision the run
  // runs, not the workflow's latest.
  @Test
  void buildsTheLayersForAWorkspaceWithContextFromTheRevision() {
    when(parameterService.getAllUnfiltered()).thenReturn(List.of(param("g1", "text", "GV")));
    workspace("ws-cheer", param("w1", "text", "WV"));
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setId("wf-1");
    workflow.setName("version-analysis");
    workflow.setDisplayName("Version analysis");
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setVersion(3);

    ParamLayers layers = service.buildParamLayers("ws-cheer", workflow, revision);

    assertThat(layers.getGlobalParams()).containsEntry("g1", "GV");
    assertThat(layers.getWorkspaceParams()).containsEntry("w1", "WV");
    assertThat(layers.getContextParams())
        .containsEntry("workflow-ref", "wf-1")
        .containsEntry("workflow-name", "version-analysis")
        .containsEntry("workflow-displayname", "Version analysis")
        .containsEntry("workflow-version", 3);
  }

  private WorkflowRevisionEntity revision(String id) {
    return revision(id, "wf-1");
  }

  private WorkflowRevisionEntity revision(String id, String workflowRef) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setId(workflowRef);
    when(workflowRepository.findById(workflowRef)).thenReturn(Optional.of(workflow));
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setId(id);
    revision.setVersion(1);
    return revision;
  }

  // Both feature switches come from the one settings document, read once per build.
  @Test
  void readsTheFeatureSwitchesOncePerBuild() {
    when(parameterService.getAllUnfiltered()).thenReturn(List.of());

    service.buildParamLayers("wf-1", revision("rev-1"));

    verify(settingsService, times(1)).getSettingByKey("features");
  }

  // Tasks admitted within the TTL share one read of each store, and each gets its own maps - the
  // engine adds per-run and per-task keys to them.
  @Test
  void servesTheLayersFromTheCacheAsSeparateCopies() {
    when(parameterService.getAllUnfiltered()).thenReturn(List.of(param("g1", "text", "GV")));
    workspace("ws-cheer", param("w1", "text", "WV"));
    owns("ws-cheer", "wf-1");
    WorkflowRevisionEntity revision = revision("rev-1");

    ParamLayers first = service.buildParamLayers("wf-1", revision);
    first.getContextParams().put("workflowrun-ref", "run-1");
    first.getGlobalParams().put("g1", "changed by a caller");
    ParamLayers second = service.buildParamLayers("wf-1", revision);

    verify(parameterService, times(1)).getAllUnfiltered();
    verify(workspaceRepository, times(1)).findById("ws-cheer");
    verify(workflowRepository, times(1)).findById("wf-1");
    assertThat(second.getGlobalParams()).containsEntry("g1", "GV");
    assertThat(second.getContextParams()).containsEntry("workflowrun-ref", "");
  }

  // A write on this instance clears the cache, so the next build reads the stores again.
  @Test
  void readsTheStoresAgainAfterAnEviction() {
    ParamLayerCache cache = new ParamLayerCache(true, Duration.ofSeconds(10), 1000);
    ParamLayerService cached =
        new ParamLayerService(
            settingsService,
            workspaceRepository,
            parameterService,
            tokenRepository,
            workflowRepository,
            workflowRevisionRepository,
            cache,
            relationshipService);
    when(parameterService.getAllUnfiltered())
        .thenReturn(List.of(param("g1", "text", "before")))
        .thenReturn(List.of(param("g1", "text", "after")));
    WorkflowRevisionEntity revision = revision("rev-1");

    cached.buildParamLayers("wf-1", revision);
    cache.evictAll();

    assertThat(cached.buildParamLayers("wf-1", revision).getGlobalParams())
        .containsEntry("g1", "after");
  }

  // Layers are cached per workflow and revision: a workflow another workspace owns reads that
  // workspace's values.
  @Test
  void cachesTheLayersPerWorkflowAndRevision() {
    when(parameterService.getAllUnfiltered()).thenReturn(List.of());
    workspace("ws-cheer", param("w1", "text", "cheer value"));
    workspace("ws-other", param("w1", "text", "other value"));
    owns("ws-cheer", "wf-1");
    owns("ws-other", "wf-2");

    service.buildParamLayers("wf-1", revision("rev-1"));

    assertThat(service.buildParamLayers("wf-2", revision("rev-2", "wf-2")).getWorkspaceParams())
        .containsEntry("w1", "other value");
  }

  // A workflow's owner is read once and kept: it never changes, and a rename does not touch the id.
  // A workflow no workspace owns gets no workspace layer, and is asked again next time.
  @Test
  void readsAWorkflowsOwnerOnceAndAnUnownedOneEachTime() {
    owns("ws-cheer", "wf-1");
    ParamLayerCache cache = new ParamLayerCache(true, Duration.ofSeconds(10), 1000);
    ParamLayerService cached =
        new ParamLayerService(
            settingsService,
            workspaceRepository,
            parameterService,
            tokenRepository,
            workflowRepository,
            workflowRevisionRepository,
            cache,
            relationshipService);

    assertThat(cached.owningWorkspace("wf-1")).isEqualTo("ws-cheer");
    cache.evictAll();
    assertThat(cached.owningWorkspace("wf-1")).isEqualTo("ws-cheer");
    assertThat(cached.owningWorkspace("wf-unowned")).isNull();
    assertThat(cached.owningWorkspace("wf-unowned")).isNull();
    assertThat(cached.owningWorkspace(null)).isNull();

    verify(relationshipService, times(1))
        .getParentByLabel(RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, "wf-1");
    verify(relationshipService, times(2))
        .getParentByLabel(RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, "wf-unowned");
  }

  // A TTL of zero turns the cache off: every build reads the stores.
  @Test
  void aZeroTtlReadsTheStoresEveryTime() {
    ParamLayerService uncached = service(Duration.ZERO);
    when(parameterService.getAllUnfiltered()).thenReturn(List.of());
    WorkflowRevisionEntity revision = revision("rev-1");
    when(workflowRevisionRepository.findById("rev-1")).thenReturn(Optional.of(revision));

    uncached.buildParamLayers("wf-1", revision);
    uncached.buildParamLayers("wf-1", revision);
    uncached.getRevision("rev-1");
    uncached.getRevision("rev-1");

    verify(parameterService, times(2)).getAllUnfiltered();
    verify(workflowRevisionRepository, times(2)).findById("rev-1");
  }

  // Revisions are read by id once per TTL; an unknown id is null and is not remembered.
  @Test
  void cachesRevisionsById() {
    WorkflowRevisionEntity revision = revision("rev-1");
    when(workflowRevisionRepository.findById("rev-1")).thenReturn(Optional.of(revision));
    when(workflowRevisionRepository.findById("missing")).thenReturn(Optional.empty());

    assertThat(service.getRevision("rev-1")).isSameAs(revision);
    assertThat(service.getRevision("rev-1")).isSameAs(revision);
    assertThat(service.getRevision("missing")).isNull();
    assertThat(service.getRevision("missing")).isNull();
    assertThat(service.getRevision(null)).isNull();

    verify(workflowRevisionRepository, times(1)).findById("rev-1");
    verify(workflowRevisionRepository, times(2)).findById("missing");
  }
}
