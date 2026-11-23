package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
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
 * Only an administrator or a global token sets a workspace's quotas: on a create, on a patch and
 * by resetting them. A workspace's owners and editors are refused with 403 and nothing changes,
 * and a patch that carries no quotas is theirs as before. Every request goes through the real
 * filter chain.
 */
class WorkspaceQuotaAuthorityTest extends AbstractEngineIntegrationTest {

  private static final Map<String, Object> UNLIMITED_MONTH =
      Map.of("quotas", Map.of("maxCumulativeDuration", 0, "maxWorkflowRunMonthly", 0));

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private UserRepository userRepository;
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
    setFeatureSetting("autoCreateUsers", true);
    UserEntity anchor = new UserEntity();
    anchor.setEmail("quota-authority-anchor-" + UUID.randomUUID() + "@example.test");
    anchor.setName("anchor");
    userRepository.save(anchor);
    workspace = workspace();
  }

  @Test
  void anOwnerOrAnEditorCannotSetOrResetTheWorkspacesQuotas() throws Exception {
    for (String role : List.of("owner", "editor")) {
      String member = signedInMember(role);

      MvcResult patched = as(member, json(patch(path()), UNLIMITED_MONTH));
      assertThat(patched.getResponse().getStatus()).as(role + " patches quotas").isEqualTo(403);
      assertThat(reasonOf(patched)).isEqualTo("PERMISSION_DENIED");
      assertThat(as(member, delete(path() + "/quotas")).getResponse().getStatus())
          .as(role + " resets quotas")
          .isEqualTo(403);
      MvcResult created =
          as(
              member,
              json(
                  post("/api/v2/workspace"),
                  Map.of(
                      "name", "quota-authority-own-" + UUID.randomUUID().toString().substring(0, 8),
                      "displayName", "Own quotas",
                      "quotas", Map.of("maxWorkflowRunMonthly", 0))));
      assertThat(created.getResponse().getStatus()).as(role + " creates with quotas").isEqualTo(403);
    }
    Quotas stored = storedQuotas();
    assertThat(stored.getMaxCumulativeDuration()).as("nothing changed").isEqualTo(600);
    assertThat(stored.getMaxWorkflowRunMonthly()).isEqualTo(50);
  }

  @Test
  void aPatchWithoutQuotasIsStillAnEditorsToMake() throws Exception {
    String editor = signedInMember("editor");

    assertThat(as(editor, json(patch(path()), Map.of("displayName", "Renamed by an editor")))
            .getResponse()
            .getStatus())
        .isEqualTo(200);
    assertThat(storedQuotas().getMaxCumulativeDuration()).isEqualTo(600);
  }

  @Test
  void anAdministratorAndAGlobalTokenSetAndResetQuotas() throws Exception {
    String admin = signIn(email());
    UserEntity adminUser = userRepository.findById(principalOf(admin)).orElseThrow();
    adminUser.setType(UserType.admin);
    adminUser.setStatus(UserStatus.active);
    userRepository.save(adminUser);

    assertThat(as(admin, json(patch(path()), UNLIMITED_MONTH)).getResponse().getStatus())
        .as("an admin patches quotas")
        .isEqualTo(200);
    assertThat(storedQuotas().getMaxCumulativeDuration()).isZero();
    assertThat(as(admin, delete(path() + "/quotas")).getResponse().getStatus())
        .as("an admin resets quotas")
        .isEqualTo(200);
    assertThat(storedQuotas().getMaxCumulativeDuration()).isNull();

    String global = mint(List.of("**/**"));
    assertThat(
            bearer(global, json(patch(path()), Map.of("quotas", Map.of("maxCumulativeDuration", 900))))
                .getResponse()
                .getStatus())
        .as("a global token patches quotas")
        .isEqualTo(200);
    assertThat(storedQuotas().getMaxCumulativeDuration()).isEqualTo(900);
    String name = "quota-authority-global-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(
            bearer(
                    global,
                    json(
                        post("/api/v2/workspace"),
                        Map.of(
                            "name", name,
                            "displayName", name,
                            "quotas", Map.of("maxCumulativeDuration", 300))))
                .getResponse()
                .getStatus())
        .as("a global token creates a workspace with quotas")
        .isEqualTo(200);
    assertThat(workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getQuotas().getMaxCumulativeDuration())
        .isEqualTo(300);
    assertThat(bearer(global, delete(path() + "/quotas")).getResponse().getStatus())
        .as("a global token resets quotas")
        .isEqualTo(200);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String path() {
    return "/api/v2/workspace/" + workspace;
  }

  private Quotas storedQuotas() {
    WorkspaceEntity entity = workspaceRepository.findByNameIgnoreCase(workspace).orElseThrow();
    return entity.getQuotas();
  }

  private String signedInMember(String role) {
    String email = email();
    String session = signIn(email);
    WorkspaceMember member = new WorkspaceMember();
    member.setEmail(email);
    member.setRole(role);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setMembers(List.of(member));
    asAdmin(() -> workspaceService.patch(workspace, request));
    return session;
  }

  private String signIn(String email) {
    try {
      return tokenService.createSessionTokenWithRaw(email, "Quota", "Authority", false, true).rawToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private String principalOf(String session) {
    return tokenService.get(session).getPrincipal();
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

  /** Mints a global token as an administrator and returns its raw value. */
  private String mint(List<String> permissions) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("quota-authority-global");
    request.setPermissions(permissions);
    return asAdmin(() -> tokenService.create(request).getToken());
  }

  // A workspace with quotas of its own, set by an administrator.
  private String workspace() {
    String name = "quota-authority-ws-" + UUID.randomUUID().toString().substring(0, 8);
    asAdmin(
        () -> {
          Quotas quotas = new Quotas();
          quotas.setMaxCumulativeDuration(600);
          quotas.setMaxWorkflowRunMonthly(50);
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(name);
          request.setDisplayName(name);
          request.setQuotas(quotas);
          return workspaceService.create(request);
        });
    return name;
  }

  /** Runs the call as a global caller, then leaves no identity behind for the next request. */
  private <T> T asAdmin(Supplier<T> call) {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("quota-authority-admin");
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
    return "quota-authority-" + UUID.randomUUID() + "@example.test";
  }
}
