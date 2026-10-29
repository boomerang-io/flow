package io.boomerang.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.TokenService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractSpiedEngineIntegrationTest;
import io.boomerang.integrations.model.GHLinkRequest;
import io.boomerang.integrations.repository.IntegrationsRepository;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Disconnecting GitHub from a workspace names the installation by the integration record id the
 * webapp holds, or by the GitHub installation id. Only a caller who can reach the workspace may do
 * it, only for an installation that workspace owns, and the link, the record and the parameters it
 * managed go together.
 */
class GitHubUnlinkTest extends AbstractSpiedEngineIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private IntegrationService integrationService;
  @Autowired private GitHubService gitHubService;
  @Autowired private IntegrationsRepository integrationsRepository;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private TokenService tokenService;
  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  @BeforeEach
  void stubTheToken() {
    setFeatureSetting("workspaceParameters", true);
    doReturn("ghs_unlink_token").when(spiedGitHubService).installationToken(anyString());
  }

  @Test
  void theRecordIdTheWebappSendsUnlinksOverHttp() throws Exception {
    String workspace = linkedWorkspace("unlink-http-ws", 5301);
    integrationService.share("github", workspace, true, null);
    String recordId = recordId(5301);
    MockMvc mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    String bearer = globalBearer();
    // The request ends by clearing the thread's security context; the checks after it read as the test.
    Authentication testIdentity = SecurityContextHolder.getContext().getAuthentication();

    mockMvc
        .perform(
            post("/api/v2/integration/github/unlink")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"" + workspace + "\",\"ref\":\"" + recordId + "\"}"))
        .andExpect(status().isOk());

    SecurityContextHolder.getContext().setAuthentication(testIdentity);
    assertUnlinked(workspace, 5301);
  }

  @Test
  void theInstallationIdStillUnlinks() {
    String workspace = linkedWorkspace("unlink-installation-ws", 5302);
    integrationService.share("github", workspace, true, null);

    gitHubService.unlinkAppInstallation(request(workspace, "5302"));

    assertUnlinked(workspace, 5302);
  }

  @Test
  void aCallerWhoCannotReachTheWorkspaceChangesNothing() {
    String target = linkedWorkspace("unlink-target-ws", 5303);
    integrationService.share("github", target, true, null);
    String recordId = recordId(5303);
    String own = workspace("unlink-own-ws");
    Authentication previous = SecurityContextHolder.getContext().getAuthentication();
    try {
      principalOwning("unlink-outsider", own);

      // By either name for the installation: the workspace is out of the caller's reach.
      for (String ref : List.of("5303", recordId)) {
        assertThatThrownBy(() -> gitHubService.unlinkAppInstallation(request(target, ref)))
            .isInstanceOfSatisfying(
                BoomerangException.class,
                e -> assertThat(e.getReason()).isEqualTo("WORKSPACE_INVALID_REF"));
      }
    } finally {
      SecurityContextHolder.getContext().setAuthentication(previous);
    }

    assertLinked(target, 5303);
  }

  @Test
  void aWorkspaceThatDoesNotOwnTheInstallationIsRefused() {
    String owner = linkedWorkspace("unlink-owner-ws", 5304);
    String other = linkedWorkspace("unlink-other-ws", 5305);
    integrationService.share("github", owner, true, null);

    assertThatThrownBy(() -> gitHubService.unlinkAppInstallation(request(other, recordId(5304))))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_UNAUTHORIZED"));

    assertLinked(owner, 5304);
  }

  @Test
  void anUnknownInstallationIsNotFound() {
    String workspace = linkedWorkspace("unlink-unknown-ws", 5306);

    assertThatThrownBy(() -> gitHubService.unlinkAppInstallation(request(workspace, "999999")))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_INVALID_REF"));
  }

  private void assertUnlinked(String workspace, int installationId) {
    assertThat(integrationsRepository.findByRef(String.valueOf(installationId))).isEmpty();
    assertThat(relationshipService.refsInWorkspace(RelationshipType.INTEGRATION, workspace, Optional.empty()))
        .isEmpty();
    assertThat(stored(workspace).getParameters()).isEmpty();
  }

  private void assertLinked(String workspace, int installationId) {
    assertThat(integrationsRepository.findByRef(String.valueOf(installationId))).isPresent();
    assertThat(relationshipService.refsInWorkspace(RelationshipType.INTEGRATION, workspace, Optional.empty()))
        .containsExactly(recordId(installationId));
    assertThat(stored(workspace).getParameters()).isNotEmpty();
  }

  private static GHLinkRequest request(String workspace, String ref) {
    GHLinkRequest request = new GHLinkRequest();
    request.setWorkspace(workspace);
    request.setRef(ref);
    return request;
  }

  private String recordId(int installationId) {
    return integrationsRepository.findByRef(String.valueOf(installationId)).orElseThrow().getId();
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    workspaceService.create(request);
    return name;
  }

  // A workspace with the GitHub App installation linked to it, as the install callback leaves it.
  private String linkedWorkspace(String name, int installationId) {
    workspace(name);
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

  private WorkspaceEntity stored(String name) {
    return workspaceRepository.findByNameIgnoreCase(name).orElseThrow();
  }

  // A signed-in user who owns one workspace and is no member of any other.
  private void principalOwning(String userRef, String workspace) {
    relationshipService.createNode(RelationshipType.USER, userRef, userRef, Optional.empty());
    relationshipService.createEdge(
        RelationshipType.USER,
        userRef,
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        workspace,
        Optional.empty());
    Token principal = new Token(AuthScope.session);
    principal.setPrincipal(userRef);
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, workspace, List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userRef, null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String globalBearer() {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("github-unlink-" + UUID.randomUUID());
    request.setPermissions(List.of("**/**"));
    return "Bearer " + tokenService.create(request).getToken();
  }
}
