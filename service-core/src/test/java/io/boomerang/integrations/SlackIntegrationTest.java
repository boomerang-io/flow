package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.slack.api.methods.response.oauth.OAuthV2AccessResponse;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractSpiedEngineIntegrationTest;
import io.boomerang.integrations.entity.IntegrationsEntity;
import io.boomerang.integrations.enums.IntegrationStatus;
import io.boomerang.integrations.model.GHLinkRequest;
import io.boomerang.integrations.model.Integration;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slack is a workspace integration: an install started from a workspace's Integrations page carries
 * a signed state, and Slack's redirect links the installation to that workspace, which may then
 * share the bot token with its tasks as a managed parameter. A forged state or a non-member links
 * nothing, a workspace links one Slack team, a rotating token is never shared, disconnecting
 * removes one workspace's link and parameter, and uninstalling the app removes them all.
 */
class SlackIntegrationTest extends AbstractSpiedEngineIntegrationTest {

  private static final String CLIENT_SECRET = "slack-integration-client-secret";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private SlackService slackService;
  @Autowired private IntegrationService integrationService;
  @Autowired private IntegrationsRepository integrationsRepository;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private MongoTemplate mongoTemplate;

  private String previousClientId;
  private String previousClientSecret;

  @BeforeEach
  void configureSlack() {
    setFeatureSetting("workspaceParameters", true);
    previousClientId = setSettingValue("integration", "slack.clientId", "slack-client-id");
    previousClientSecret = setSettingValue("integration", "slack.clientSecret", CLIENT_SECRET);
  }

  @AfterEach
  void restoreSlack() {
    setSettingValue("integration", "slack.clientId", previousClientId);
    setSettingValue("integration", "slack.clientSecret", previousClientSecret);
  }

  @Test
  void aSignedInstallLinksTheSlackTeamToTheWorkspace() {
    String workspace = workspaceWithMember("slack-link-ws", "slack-link-user");
    Integration before = slack(workspace);
    assertThat(before.getStatus()).isEqualTo(IntegrationStatus.unlinked);
    assertThat(before.getLink())
        .startsWith("https://slack.com/oauth/v2/authorize?client_id=slack-client-id&scope=")
        .contains("&state=");
    authorizes("T100", "xoxb-link-token", null, null);

    ResponseEntity<?> response =
        slackService.handleAuth("code", InstallStateUtils.sign(workspace, "slack-link-user", CLIENT_SECRET));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getLocation()).hasToString("/apps/flow/slack-link-ws/integrations");
    IntegrationsEntity installation = installation("T100");
    assertThat(installation.getType()).isEqualTo("slack_auth");
    assertThat(linked(workspace)).containsExactly(installation.getId());
    Integration after = slack(workspace);
    assertThat(after.getStatus()).isEqualTo(IntegrationStatus.linked);
    assertThat(after.getShared()).isFalse();
    assertThat(after.getFields()).extracting(AbstractParam::getName).containsExactly("slack-token");
  }

  @Test
  void aForgedStateANonMemberOrASecondSlackTeamLinksNothing() {
    String workspace = workspaceWithMember("slack-refused-ws", "slack-refused-user");

    assertThatThrownBy(() -> slackService.handleAuth("code", "not-a-signed-state"))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_INVALID_STATE"));
    // A state another integration signed is not this one's.
    assertThatThrownBy(
            () -> slackService.handleAuth("code", InstallStateUtils.sign(workspace, "slack-refused-user", "other")))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_INVALID_STATE"));
    assertThatThrownBy(
            () -> slackService.handleAuth("code", InstallStateUtils.sign(workspace, "not-a-member", CLIENT_SECRET)))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_UNAUTHORIZED"));
    // All three are refused before Slack is asked for a token.
    verify(spiedSlackService, never()).exchangeCode(anyString());
    assertThat(linked(workspace)).isEmpty();

    install(workspace, "slack-refused-user", "T200", "xoxb-first-team");
    authorizes("T201", "xoxb-second-team", null, null);
    assertThatThrownBy(
            () -> slackService.handleAuth("code", InstallStateUtils.sign(workspace, "slack-refused-user", CLIENT_SECRET)))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_ALREADY_LINKED"));
    assertThat(linked(workspace)).containsExactly(installation("T200").getId());
  }

  @Test
  void sharingWritesTheBotTokenEncryptedAndARotatingTokenIsRefused() {
    String workspace = workspaceWithMember("slack-share-ws", "slack-share-user");
    install(workspace, "slack-share-user", "T300", "xoxb-shared-token");

    integrationService.share("slack", workspace, true);

    WorkspaceEntity stored = workspace(workspace);
    assertThat(param(stored, "slack-token"))
        .satisfies(p -> assertThat(p.isReadOnly()).isTrue())
        .satisfies(p -> assertThat(p.getType()).isEqualTo("password"))
        .satisfies(p -> assertThat(p.getValue()).isEqualTo("xoxb-shared-token"));
    assertThat(rawValue(stored.getId(), "slack-token")).startsWith("crypt_v1").doesNotContain("xoxb-shared-token");
    assertThat(slack(workspace).getShared()).isTrue();

    String rotating = workspaceWithMember("slack-rotating-ws", "slack-rotating-user");
    authorizes("T301", "xoxe.xoxb-rotating", "xoxe-1-refresh", 43200);
    slackService.handleAuth("code", InstallStateUtils.sign(rotating, "slack-rotating-user", CLIENT_SECRET));

    assertThatThrownBy(() -> integrationService.share("slack", rotating, true))
        .isInstanceOfSatisfying(
            BoomerangException.class, e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_TOKEN_ROTATES"));
    assertThat(workspace(rotating).getParameters()).extracting(AbstractParam::getName).doesNotContain("slack-token");
  }

  @Test
  void disconnectingRemovesOneWorkspaceAndUninstallingRemovesThemAll() {
    String first = workspaceWithMember("slack-unlink-first-ws", "slack-unlink-user");
    String second = workspaceWithMember("slack-unlink-second-ws", "slack-unlink-user");
    install(first, "slack-unlink-user", "T400", "xoxb-team-token");
    install(second, "slack-unlink-user", "T400", "xoxb-team-token");
    integrationService.share("slack", first, true);
    integrationService.share("slack", second, true);
    String installationId = installation("T400").getId();
    GHLinkRequest unlink = new GHLinkRequest();
    unlink.setRef(installationId);
    unlink.setWorkspace(first);

    slackService.unlink(unlink);

    assertThat(linked(first)).isEmpty();
    assertThat(workspace(first).getParameters()).extracting(AbstractParam::getName).doesNotContain("slack-token");
    assertThat(linked(second)).containsExactly(installationId);
    assertThat(param(workspace(second), "slack-token").getValue()).isEqualTo("xoxb-team-token");

    slackService
        .appUninstalled(JSON.readTree("{\"team_id\":\"T400\",\"event\":{\"type\":\"app_uninstalled\"}}"))
        .get();

    assertThat(integrationsRepository.findById(installationId)).isEmpty();
    assertThat(linked(second)).isEmpty();
    assertThat(workspace(second).getParameters()).extracting(AbstractParam::getName).doesNotContain("slack-token");
  }

  // Slack answers the code exchange for this team with this bot token, rotating when given a refresh.
  private void authorizes(String teamId, String token, String refreshToken, Integer expiresIn) {
    OAuthV2AccessResponse response = new OAuthV2AccessResponse();
    response.setOk(true);
    response.setTokenType("bot");
    response.setAccessToken(token);
    response.setRefreshToken(refreshToken);
    response.setExpiresIn(expiresIn);
    OAuthV2AccessResponse.Team team = new OAuthV2AccessResponse.Team();
    team.setId(teamId);
    team.setName(teamId);
    response.setTeam(team);
    doReturn(response).when(spiedSlackService).exchangeCode(anyString());
  }

  private void install(String workspace, String user, String teamId, String token) {
    authorizes(teamId, token, null, null);
    slackService.handleAuth("code", InstallStateUtils.sign(workspace, user, CLIENT_SECRET));
  }

  private String workspaceWithMember(String name, String user) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    workspaceService.create(request);
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.USER, user)) {
      relationshipService.createNode(RelationshipType.USER, user, user, Optional.empty());
    }
    relationshipService.createEdge(
        RelationshipType.USER,
        user,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        name,
        Optional.of(Map.of("role", "owner")));
    return name;
  }

  private Integration slack(String workspace) {
    return integrationService.get(workspace).stream()
        .filter(i -> "Slack".equals(i.getName()))
        .findFirst()
        .orElseThrow();
  }

  private IntegrationsEntity installation(String teamId) {
    return integrationsRepository.findByRef(teamId).orElseThrow();
  }

  private List<String> linked(String workspace) {
    return relationshipService.findNodeRefs(RelationshipType.WORKSPACE, workspace, RelationshipType.INTEGRATION);
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
