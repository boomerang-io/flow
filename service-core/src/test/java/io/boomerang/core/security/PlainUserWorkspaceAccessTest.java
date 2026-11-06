package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * What a plain user signed in through the webapp reaches in a workspace, through the real filter
 * chain and real stores. The person signs in first and is made a member afterwards, as happens
 * when an administrator adds an existing user to a workspace: the session cookie they already
 * hold must carry the membership on their next request, with the access their role grants and no
 * more.
 */
class PlainUserWorkspaceAccessTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "plain-user-access-task";
  // The list routes the webapp reads; the task run search needs a label or a run to search by.
  private static final List<String> WORKSPACE_READS =
      List.of(
          "workflow/query",
          "workflowrun/query",
          "schedule/query",
          "action/query",
          "taskrun/query?labels=purpose%3Daccess-test");

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;
  @Autowired private WorkflowService workflowService;
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting("autoCreateUsers", true);
    seedGlobalTask(TASK_SLUG);
    // The install has users, so no sign-in here is the first one.
    UserEntity anchor = new UserEntity();
    anchor.setEmail("anchor-" + UUID.randomUUID() + "@example.test");
    anchor.setName("anchor");
    userRepository.save(anchor);
  }

  @ParameterizedTest
  @ValueSource(strings = {"owner", "editor", "reader"})
  void aMemberAddedAfterSigningInReadsTheWorkspace(String role) throws Exception {
    String workspace = workspace();
    String email = email();
    String session = signIn(email);

    addMember(workspace, email, role);

    for (String resource : WORKSPACE_READS) {
      as(session, get("/api/v2/workspace/" + workspace + "/" + resource))
          .andExpect(
              result ->
                  assertThat(result.getResponse().getStatus())
                      .as("%s: %s", resource, result.getResponse().getContentAsString())
                      .isEqualTo(200));
    }
  }

  @Test
  void aReaderCannotCreateAWorkflow() throws Exception {
    String workspace = workspace();
    String email = email();
    String session = signIn(email);
    addMember(workspace, email, "reader");

    as(session, createWorkflow(workspace, "plain-user-reader-wf"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.reason").value("PERMISSION_DENIED"));
  }

  @Test
  void anEditorCreatesAWorkflowAndReadsItBack() throws Exception {
    String workspace = workspace();
    String email = email();
    String session = signIn(email);
    addMember(workspace, email, "editor");

    as(session, createWorkflow(workspace, "plain-user-editor-wf")).andExpect(status().isOk());
    as(session, get("/api/v2/workspace/" + workspace + "/workflow/plain-user-editor-wf"))
        .andExpect(status().isOk());
  }

  @Test
  void aPlainUserCreatingAWorkspaceReadsItWithTheSameSession() throws Exception {
    String session = signIn(email());
    String workspace = "plain-user-own-" + UUID.randomUUID().toString().substring(0, 8);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(workspace);
    request.setDisplayName("Plain user own workspace");

    as(
            session,
            post("/api/v2/workspace")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    as(session, get("/api/v2/workspace/" + workspace + "/workflow/query"))
        .andExpect(status().isOk());
  }

  @Test
  void aMemberOfOneWorkspaceDoesNotReachAnother() throws Exception {
    String mine = workspace();
    String theirs = workspace();
    createWorkflowAsAdmin(theirs, "plain-user-theirs-wf");
    String email = email();
    String session = signIn(email);
    addMember(mine, email, "owner");

    int workspaceStatus =
        as(session, get("/api/v2/workspace/" + theirs)).andReturn().getResponse().getStatus();
    int workflowStatus =
        as(session, get("/api/v2/workspace/" + theirs + "/workflow/plain-user-theirs-wf"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(workspaceStatus).isIn(403, 404);
    assertThat(workflowStatus).isIn(403, 404);
    // A grant for their own workspace does not count in another, so the query is refused.
    as(session, get("/api/v2/workspace/" + theirs + "/workflow/query"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aUserInNoWorkspaceIsRefusedAWorkspaceRead() throws Exception {
    String workspace = workspace();
    String session = signIn(email());

    as(session, get("/api/v2/workspace/" + workspace + "/workflow/query"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aRemovedMemberLosesTheWorkspaceOnTheirNextRequest() throws Exception {
    String workspace = workspace();
    String email = email();
    String session = signIn(email);
    // An editor: the last owner stays until another member is made one.
    addMember(workspace, email, "editor");
    as(session, get("/api/v2/workspace/" + workspace + "/workflow/query"))
        .andExpect(status().isOk());

    WorkspaceMember removed = member(email, "editor");
    removed.setId(userRepository.findByEmail(email).getId());
    asAdmin(() -> workspaceService.removeMembers(workspace, List.of(removed)));

    as(session, get("/api/v2/workspace/" + workspace + "/workflow/query"))
        .andExpect(status().isForbidden());
  }

  // The webapp changes a member's role by sending the member again.
  @Test
  void sendingAMemberAgainChangesTheirRoleOnTheirNextRequest() throws Exception {
    String workspace = workspace();
    String email = email();
    String session = signIn(email);
    addMember(workspace, email, "editor");
    as(session, createWorkflow(workspace, "plain-user-before-wf")).andExpect(status().isOk());

    addMember(workspace, email, "reader");

    as(session, createWorkflow(workspace, "plain-user-after-wf"))
        .andExpect(status().isForbidden());
    String userId = userRepository.findByEmail(email).getId();
    asAdmin(
        () ->
            assertThat(relationshipService.membersAndRoles(workspace))
                .containsEntry(userId, "reader"));
    assertThat(relationshipService.roles(userId)).hasSize(1).containsValue("reader");
  }

  @Test
  void aSessionWhoseUserIsGoneAuthenticatesAsNobody() throws Exception {
    String email = email();
    String session = signIn(email);
    userRepository.deleteById(userRepository.findByEmail(email).getId());

    as(session, get("/api/v2/profile")).andExpect(status().isUnauthorized());
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** Signs the person in as the webapp's exchange does and returns the session cookie's value. */
  private String signIn(String email) {
    try {
      return tokenService.createSessionTokenWithRaw(email, "Plain", "User", false, true).rawToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private ResultActions as(String session, MockHttpServletRequestBuilder request)
      throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request.cookie(new Cookie(SessionCookie.NAME, session)));
  }

  /** Creates a workspace as an administrator, with no members. */
  private String workspace() {
    String name = "plain-user-ws-" + UUID.randomUUID().toString().substring(0, 8);
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(name);
          request.setDisplayName(name);
          workspaceService.create(request);
        });
    return name;
  }

  /** {@code PATCH /api/v2/workspace/{workspace} {members:[{email, role}]}}, as an administrator. */
  private void addMember(String workspace, String email, String role) {
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setMembers(List.of(member(email, role)));
          workspaceService.patch(workspace, request);
        });
  }

  private void createWorkflowAsAdmin(String workspace, String name) {
    asAdmin(() -> workflowService.create(workspace, runnableWorkflow(name, TASK_SLUG)));
  }

  private MockHttpServletRequestBuilder createWorkflow(String workspace, String name)
      throws Exception {
    return post("/api/v2/workspace/" + workspace + "/workflow")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(runnableWorkflow(name, TASK_SLUG)));
  }

  /** Runs the action as a global caller, then leaves no identity behind for the next request. */
  private void asAdmin(Runnable action) {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("plain-user-access-admin");
    admin.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(admin.getPrincipal(), null);
    authentication.setDetails(admin);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      action.run();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static WorkspaceMember member(String email, String role) {
    WorkspaceMember member = new WorkspaceMember();
    member.setEmail(email);
    member.setRole(role);
    return member;
  }

  private static String email() {
    return "plain-user-" + UUID.randomUUID() + "@example.test";
  }
}
