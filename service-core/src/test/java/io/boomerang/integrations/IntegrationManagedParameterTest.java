package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractSpiedEngineIntegrationTest;
import io.boomerang.integrations.model.GHLinkRequest;
import io.boomerang.integrations.model.Integration;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The GitHub integration shares its installation token with a workspace's tasks as managed
 * workspace parameters: written read-only and encrypted when sharing is turned on, refused when a
 * user's parameter holds the name or workspace parameters are off, never changed or removed through
 * the workspace, refreshed in place, and gone when the installation is unlinked or uninstalled.
 */
class IntegrationManagedParameterTest extends AbstractSpiedEngineIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private IntegrationService integrationService;
  @Autowired private GitHubService gitHubService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void turnOnWorkspaceParameters() {
    setFeatureSetting("workspaceParameters", true);
    doReturn("ghs_first_token").when(spiedGitHubService).installationToken(anyString());
  }

  @AfterEach
  void restoreWorkspaceParameters() {
    setFeatureSetting("workspaceParameters", true);
  }

  @Test
  void sharingWritesTheManagedParametersWithTheTokenEncryptedAtRest() {
    String workspace = linkedWorkspace("managed-share-ws", 5101);

    integrationService.shareGitHub(workspace, true);

    WorkspaceEntity stored = workspace(workspace);
    assertThat(param(stored, "github-token"))
        .satisfies(p -> assertThat(p.isReadOnly()).isTrue())
        .satisfies(p -> assertThat(p.getType()).isEqualTo("password"))
        .satisfies(p -> assertThat(p.getValue()).isEqualTo("ghs_first_token"));
    assertThat(param(stored, "github-url"))
        .satisfies(p -> assertThat(p.isReadOnly()).isTrue())
        .satisfies(p -> assertThat(p.getValue()).isEqualTo("https://api.github.com"));
    assertThat(rawValue(stored.getId(), "github-token")).startsWith("crypt_v1").doesNotContain("ghs_first_token");
    Integration github =
        integrationService.get(workspace).stream()
            .filter(i -> "GitHub".equals(i.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(github.getShared()).isTrue();
    assertThat(github.getFields()).extracting(AbstractParam::getName).containsExactly("github-token", "github-url");

    integrationService.shareGitHub(workspace, false);

    assertThat(workspace(workspace).getParameters())
        .extracting(AbstractParam::getName)
        .doesNotContain("github-token", "github-url");
  }

  @Test
  void sharingIsRefusedOverAUsersParameterOrWithWorkspaceParametersOff() {
    String workspace = linkedWorkspace("managed-refused-ws", 5102);
    patch(workspace, userParam("github-token", "my-personal-token"));

    assertThatThrownBy(() -> integrationService.shareGitHub(workspace, true))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_PARAM_EXISTS"));
    assertThat(param(workspace(workspace), "github-token").getValue()).isEqualTo("my-personal-token");

    String other = linkedWorkspace("managed-flag-off-ws", 5103);
    setFeatureSetting("workspaceParameters", false);
    assertThatThrownBy(() -> integrationService.shareGitHub(other, true))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_PARAMS_DISABLED"));
    // Both checks come first: no token is minted for a share that is refused.
    verify(spiedGitHubService, never()).installationToken(anyString());
  }

  @Test
  void aManagedParameterIsNeverChangedOrRemovedThroughTheWorkspace() {
    String workspace = linkedWorkspace("managed-protected-ws", 5104);
    integrationService.shareGitHub(workspace, true);

    assertThatThrownBy(() -> patch(workspace, userParam("github-token", "overwritten")))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("PARAMS_MANAGED_BY_INTEGRATION"));
    assertThatThrownBy(() -> workspaceService.deleteParameter(workspace, "github-url"))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("PARAMS_MANAGED_BY_INTEGRATION"));

    // Read-only is the server's to set: a client cannot make a parameter look managed.
    AbstractParam forged = userParam("github-forged", "value");
    forged.setReadOnly(true);
    patch(workspace, forged);

    WorkspaceEntity stored = workspace(workspace);
    assertThat(param(stored, "github-token").getValue()).isEqualTo("ghs_first_token");
    assertThat(param(stored, "github-url").getValue()).isEqualTo("https://api.github.com");
    assertThat(param(stored, "github-forged").isReadOnly()).isFalse();
    workspaceService.deleteParameter(workspace, "github-forged");
  }

  @Test
  void unlinkingOrUninstallingRemovesOnlyTheManagedParameters() {
    String unlinked = linkedWorkspace("managed-unlink-ws", 5105);
    integrationService.shareGitHub(unlinked, true);
    patch(unlinked, userParam("kept", "mine"));
    GHLinkRequest unlink = new GHLinkRequest();
    unlink.setRef("5105");
    unlink.setWorkspace(unlinked);

    gitHubService.unlinkAppInstallation(unlink);

    assertThat(workspace(unlinked).getParameters()).extracting(AbstractParam::getName).containsExactly("kept");

    String uninstalled = linkedWorkspace("managed-uninstall-ws", 5106);
    integrationService.shareGitHub(uninstalled, true);

    integrationService.delete("github_app", JSON.readTree("{\"id\":5106}"));

    assertThat(workspace(uninstalled).getParameters()).isEmpty();
  }

  @Test
  void theRefresherReplacesOnlyTheTokenInPlace() {
    String shared = linkedWorkspace("managed-refresh-ws", 5107);
    integrationService.shareGitHub(shared, true);
    patch(shared, userParam("untouched", "as-is"));
    String notShared = linkedWorkspace("managed-not-shared-ws", 5108);
    doReturn("ghs_second_token").when(spiedGitHubService).installationToken(anyString());

    spiedGitHubService.refreshSharedTokens();

    WorkspaceEntity stored = workspace(shared);
    assertThat(param(stored, "github-token").getValue()).isEqualTo("ghs_second_token");
    assertThat(rawValue(stored.getId(), "github-token")).startsWith("crypt_v1").doesNotContain("ghs_second_token");
    assertThat(param(stored, "github-url").getValue()).isEqualTo("https://api.github.com");
    assertThat(param(stored, "untouched").getValue()).isEqualTo("as-is");
    verify(spiedGitHubService, never()).installationToken("5108");
    assertThat(workspace(notShared).getParameters()).isEmpty();
  }

  // A workspace with the GitHub App installation linked to it, as the install callback leaves it.
  private String linkedWorkspace(String name, int installationId) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    workspaceService.create(request);
    String integrationId =
        integrationService.create("github_app", JSON.readTree("{\"id\":" + installationId + "}")).getId();
    relationshipService.createEdge(
        RelationshipType.WORKSPACE,
        name,
        RelationshipLabel.HAS_INTEGRATION,
        RelationshipType.INTEGRATION,
        integrationId,
        Optional.empty());
    return name;
  }

  private void patch(String workspace, AbstractParam param) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setParameters(List.of(param));
    workspaceService.patch(workspace, request);
  }

  private static AbstractParam userParam(String name, String value) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType("text");
    param.setValue(value);
    return param;
  }

  private WorkspaceEntity workspace(String name) {
    return workspaceRepository.findByNameIgnoreCase(name).orElseThrow();
  }

  private static AbstractParam param(WorkspaceEntity workspace, String name) {
    return workspace.getParameters().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
  }

  // The value as stored, before the load hook decrypts it.
  private String rawValue(String workspaceId, String name) {
    Document stored =
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(WorkspaceEntity.class))
            .find(new Document("_id", new org.bson.types.ObjectId(workspaceId)))
            .first();
    return stored.getList("parameters", Document.class).stream()
        .filter(p -> name.equals(p.getString("name")))
        .map(p -> p.getString("value"))
        .findFirst()
        .orElseThrow();
  }
}
