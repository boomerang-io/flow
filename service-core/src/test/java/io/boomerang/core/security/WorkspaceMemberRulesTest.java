package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.integrations.GitHubService;
import io.boomerang.integrations.InstallStateUtils;
import io.boomerang.integrations.SlackService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The members of a workspace, and their roles, are changed by an owner of it, an admin or a global
 * token; a workspace that has an owner always keeps one; a personal token follows its user's
 * current memberships; linking an integration needs integration/write in the workspace; and a
 * rename takes a name no other workspace holds. Every request goes through the real filter chain.
 */
class WorkspaceMemberRulesTest extends AbstractEngineIntegrationTest {

  private static final String SLACK_SECRET = "member-rules-slack-secret";
  private static final String GITHUB_KEY = "member-rules-github-key";

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;
  @Autowired private SlackService slackService;
  @Autowired private GitHubService gitHubService;
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String workspace;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("autoCreateUsers", true);
    // An activated install: sign-in registers people without an activation code.
    UserEntity anchor = new UserEntity();
    anchor.setEmail("member-rules-anchor-" + UUID.randomUUID() + "@example.test");
    anchor.setName("anchor");
    userRepository.save(anchor);
    workspace = workspace();
  }

  @Test
  void onlyAnOwnerAnAdminOrAGlobalTokenChangesMembers() throws Exception {
    String owner = signedInMember("owner");
    String editor = signedInMember("editor");
    String readerEmail = email();
    String readerId = signInAndGetId(readerEmail);
    addMember(workspace, readerEmail, "reader");

    MvcResult add = as(editor, members(Map.of("email", email(), "role", "reader")));
    assertThat(add.getResponse().getStatus()).as("an editor adds a member").isEqualTo(403);
    assertThat(reasonOf(add)).isEqualTo("WORKSPACE_OWNER_REQUIRED");
    assertThat(as(editor, members(Map.of("id", readerId, "role", "editor"))).getResponse().getStatus())
        .as("an editor changes a role")
        .isEqualTo(403);
    assertThat(as(editor, json(patch(path()), Map.of("displayName", "Renamed by an editor")))
            .getResponse()
            .getStatus())
        .as("an editor still changes the display name")
        .isEqualTo(200);
    assertThat(roleOf(readerId)).isEqualTo("reader");

    assertThat(as(owner, members(Map.of("id", readerId, "role", "editor"))).getResponse().getStatus())
        .as("an owner changes a role")
        .isEqualTo(200);
    assertThat(roleOf(readerId)).isEqualTo("editor");
    assertThat(as(owner, members(Map.of("email", email(), "role", "reader"))).getResponse().getStatus())
        .as("an owner adds a member")
        .isEqualTo(200);

    String admin = signIn(email());
    UserEntity adminUser = userRepository.findById(principalOf(admin)).orElseThrow();
    adminUser.setType(UserType.admin);
    adminUser.setStatus(UserStatus.active);
    userRepository.save(adminUser);
    assertThat(as(admin, members(Map.of("id", readerId, "role", "reader"))).getResponse().getStatus())
        .as("an admin changes a role")
        .isEqualTo(200);
    String global = mint(AuthScope.global, null, List.of("**/**"));
    assertThat(bearer(global, members(Map.of("email", email(), "role", "editor"))).getResponse().getStatus())
        .as("a global token adds a member")
        .isEqualTo(200);
  }

  @Test
  void aWorkspaceAlwaysKeepsAnOwner() throws Exception {
    String ownerEmail = email();
    String owner = signIn(ownerEmail);
    String ownerId = principalOf(owner);
    addMember(workspace, ownerEmail, "owner");
    String otherEmail = email();
    String other = signIn(otherEmail);
    String otherId = principalOf(other);
    addMember(workspace, otherEmail, "editor");

    MvcResult removal = as(owner, json(delete(path() + "/members"), List.of(Map.of("id", ownerId))));
    assertThat(removal.getResponse().getStatus()).as("removing the last owner").isEqualTo(400);
    assertThat(reasonOf(removal)).isEqualTo("WORKSPACE_LAST_OWNER");
    MvcResult demotion = as(owner, members(Map.of("id", ownerId, "role", "editor")));
    assertThat(demotion.getResponse().getStatus()).as("demoting the last owner").isEqualTo(400);
    assertThat(reasonOf(demotion)).isEqualTo("WORKSPACE_LAST_OWNER");
    MvcResult leaving = as(owner, delete(path() + "/leave"));
    assertThat(leaving.getResponse().getStatus()).as("the last owner leaving").isEqualTo(400);
    assertThat(reasonOf(leaving)).isEqualTo("WORKSPACE_LAST_OWNER");
    assertThat(roleOf(ownerId)).isEqualTo("owner");

    assertThat(as(owner, members(Map.of("id", otherId, "role", "owner"))).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(as(owner, json(delete(path() + "/members"), List.of(Map.of("id", otherId))))
            .getResponse()
            .getStatus())
        .as("with two owners, removing one")
        .isEqualTo(200);
    assertThat(roleOf(otherId)).isNull();
    assertThat(as(owner, members(Map.of("id", otherId, "role", "owner"))).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(as(owner, delete(path() + "/leave")).getResponse().getStatus())
        .as("an owner leaving while another stays")
        .isEqualTo(200);
    assertThat(roleOf(ownerId)).isNull();

    String admin = mint(AuthScope.global, null, List.of("**/**"));
    assertThat(bearer(admin, delete(path())).getResponse().getStatus())
        .as("deleting the workspace")
        .isEqualTo(200);
  }

  @Test
  void aPersonalTokenFollowsItsUsersCurrentMemberships() throws Exception {
    String email = email();
    String session = signIn(email);
    String userId = principalOf(session);
    addMember(workspace, email(), "owner");
    addMember(workspace, email, "editor");
    String personal = personalToken(session, userId);

    assertThat(bearer(personal, get(path())).getResponse().getStatus()).isEqualTo(200);
    assertThat(bearer(personal, json(post("/api/v2/token"), keyToken(workspace))).getResponse().getStatus())
        .as("a workspace token, minted where the user edits")
        .isEqualTo(200);

    asAdmin(() -> workspaceService.patch(workspace, membersRequest(email, "reader")));
    assertThat(bearer(personal, json(patch(path()), Map.of("displayName", "Read only"))).getResponse().getStatus())
        .as("a write after the user is made a reader")
        .isEqualTo(403);

    asAdmin(() -> workspaceService.patch(workspace, membersRequest(email, "owner")));
    assertThat(bearer(personal, delete(path() + "/quotas")).getResponse().getStatus())
        .as("an owner's delete, through a token minted while the user edited")
        .isEqualTo(403);

    asAdmin(
        () -> {
          WorkspaceMember removed = new WorkspaceMember();
          removed.setId(userId);
          workspaceService.removeMembers(workspace, List.of(removed));
          return null;
        });
    assertThat(bearer(personal, get(path())).getResponse().getStatus())
        .as("a read after the user is removed")
        .isEqualTo(403);
    assertThat(bearer(personal, json(post("/api/v2/token"), keyToken(workspace))).getResponse().getStatus())
        .as("a workspace token after the user is removed")
        .isEqualTo(403);

    userRepository.deleteById(userId);
    assertThat(bearer(personal, get("/api/v2/workspace/query")).getResponse().getStatus())
        .as("a personal token whose user is gone")
        .isEqualTo(401);
  }

  @Test
  void aReaderDoesNotCompleteAnIntegrationInstall() {
    String readerEmail = email();
    String readerId = signInAndGetId(readerEmail);
    addMember(workspace, readerEmail, "reader");
    String slackSecret = setSettingValue("integration", "slack.clientSecret", SLACK_SECRET);
    String githubKey = setSettingValue("integration", "github.jwt", GITHUB_KEY);
    try {
      assertThatThrownBy(
              () ->
                  slackService.handleAuth(
                      "code", InstallStateUtils.sign(workspace, readerId, SLACK_SECRET)))
          .isInstanceOfSatisfying(
              BoomerangException.class,
              e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_UNAUTHORIZED"));
      assertThatThrownBy(
              () ->
                  gitHubService.handleInstallCallback(
                      1, "code", InstallStateUtils.sign(workspace, readerId, GITHUB_KEY)))
          .isInstanceOfSatisfying(
              BoomerangException.class,
              e -> assertThat(e.getReason()).isEqualTo("INTEGRATION_UNAUTHORIZED"));
    } finally {
      setSettingValue("integration", "slack.clientSecret", slackSecret);
      setSettingValue("integration", "github.jwt", githubKey);
    }
  }

  @Test
  void aRenameTakesANameNoOtherWorkspaceHolds() throws Exception {
    String taken = workspace();
    String global = mint(AuthScope.global, null, List.of("**/**"));

    MvcResult rename = bearer(global, json(patch(path()), Map.of("name", taken)));

    assertThat(rename.getResponse().getStatus()).isEqualTo(422);
    assertThat(reasonOf(rename)).isEqualTo("WORKSPACE_NON_UNIQUE_NAME");
    assertThat(bearer(global, get(path())).getResponse().getStatus()).isEqualTo(200);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String path() {
    return "/api/v2/workspace/" + workspace;
  }

  private MockHttpServletRequestBuilder members(Map<String, String> member) throws Exception {
    return json(patch(path()), Map.of("members", List.of(member)));
  }

  private String signedInMember(String role) {
    String email = email();
    String session = signIn(email);
    addMember(workspace, email, role);
    return session;
  }

  private String signIn(String email) {
    try {
      return tokenService.createSessionTokenWithRaw(email, "Member", "Rules", false, true).rawToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private String signInAndGetId(String email) {
    signIn(email);
    return userRepository.findByEmail(email).getId();
  }

  private String principalOf(String session) {
    return tokenService.get(session).getPrincipal();
  }

  private String roleOf(String userId) {
    return relationshipService.membersAndRoles(workspace).get(userId);
  }

  private String personalToken(String session, String userId) throws Exception {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.user);
    request.setName("member-rules-personal");
    request.setPrincipal(userId);
    MvcResult minted = as(session, json(post("/api/v2/token"), request));
    assertThat(minted.getResponse().getStatus()).isEqualTo(200);
    return objectMapper.readTree(minted.getResponse().getContentAsString()).get("token").asString();
  }

  private String reasonOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("reason").asString();
  }

  private MvcResult as(String session, MockHttpServletRequestBuilder request) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request.cookie(new Cookie(SessionCookie.NAME, session))).andReturn();
  }

  private MvcResult bearer(String token, MockHttpServletRequestBuilder request) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private static TokenCreateRequest keyToken(String workspace) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.key);
    request.setName("member-rules-key");
    request.setPrincipal(workspace);
    request.setPermissions(List.of("workflow/read"));
    return request;
  }

  /** Mints a token as an administrator and returns its raw value. */
  private String mint(AuthScope type, String principal, List<String> permissions) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(type);
    request.setName("member-rules-" + type);
    request.setPrincipal(principal);
    request.setPermissions(permissions);
    return asAdmin(() -> tokenService.create(request).getToken());
  }

  private String workspace() {
    String name = "member-rules-ws-" + UUID.randomUUID().toString().substring(0, 8);
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(name);
          request.setDisplayName(name);
          return workspaceService.create(request);
        });
    return name;
  }

  private static WorkspaceRequest membersRequest(String email, String role) {
    WorkspaceMember member = new WorkspaceMember();
    member.setEmail(email);
    member.setRole(role);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setMembers(List.of(member));
    return request;
  }

  private void addMember(String workspace, String email, String role) {
    asAdmin(() -> workspaceService.patch(workspace, membersRequest(email, role)));
  }

  /** Runs the call as a global caller, then leaves no identity behind for the next request. */
  private <T> T asAdmin(Supplier<T> call) {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("member-rules-admin");
    admin.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(admin.getPrincipal(), null);
    authentication.setDetails(admin);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      return call.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static String email() {
    return "member-rules-" + UUID.randomUUID() + "@example.test";
  }
}
