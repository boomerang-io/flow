package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.enums.UserStatus;
import io.boomerang.core.enums.UserType;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.repository.ActionRepository;
import io.boomerang.schedule.ScheduleService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * A workspace grant applies only to its workspace. A person who owns one workspace and reads
 * another writes only in the one they own, whichever route names the other workspace's object: its
 * path, an id in the body, or an id with no workspace at all. A global grant and the platform-wide
 * tokens are unaffected. Every request goes through the real filter chain, so both the route check
 * and the service checks are exercised.
 */
class WorkspaceGrantScopeTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "grant-scope-task";
  private static final String OTHER_WORKFLOW = "grant-scope-other-wf";

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowService workflowService;
  @Autowired private ScheduleService scheduleService;
  @Autowired private ActionRepository actionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String home;
  private String other;
  private String otherWorkflowId;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("autoCreateUsers", true);
    seedGlobalTask(TASK_SLUG);
    UserEntity anchor = new UserEntity();
    anchor.setEmail("grant-scope-anchor-" + UUID.randomUUID() + "@example.test");
    anchor.setName("anchor");
    userRepository.save(anchor);
    home = workspace();
    other = workspace();
    otherWorkflowId =
        asAdmin(
            () -> {
              workflowService.create(other, runnableWorkflow(OTHER_WORKFLOW, TASK_SLUG));
              return relationshipService
                  .refsInWorkspace(
                      RelationshipType.WORKFLOW, other, Optional.of(List.of(OTHER_WORKFLOW)))
                  .get(0);
            });
  }

  @Test
  void anOwnerOfOneWorkspaceAndReaderOfAnotherWritesOnlyInTheirOwn() throws Exception {
    String email = email();
    String session = signIn(email);
    addMember(home, email, "owner");
    addMember(other, email, "reader");

    Map<String, MockHttpServletRequestBuilder> writesInOther = new LinkedHashMap<>();
    writesInOther.put("workflow create", createWorkflow(other, "grant-scope-new-wf"));
    writesInOther.put(
        "workflow delete", delete("/api/v2/workspace/" + other + "/workflow/" + OTHER_WORKFLOW));
    writesInOther.put(
        "run submit",
        json(
            post("/api/v2/workspace/" + other + "/workflow/" + OTHER_WORKFLOW + "/submit"),
            new WorkflowSubmitRequest()));
    writesInOther.put(
        "schedule create",
        json(post("/api/v2/workspace/" + other + "/schedule"), schedule(OTHER_WORKFLOW)));
    writesInOther.put(
        "parameter write",
        json(
            patch("/api/v2/workspace/" + other),
            Map.of("parameters", List.of(Map.of("name", "region", "type", "string")))));
    writesInOther.put(
        "member change",
        json(
            patch("/api/v2/workspace/" + other),
            Map.of("members", List.of(Map.of("email", email, "role", "owner")))));
    writesInOther.put(
        "member removal",
        json(delete("/api/v2/workspace/" + other + "/members"), List.of(Map.of("email", email))));
    writesInOther.put("workspace token create", json(post("/api/v2/token"), keyToken(other)));

    for (Map.Entry<String, MockHttpServletRequestBuilder> write : writesInOther.entrySet()) {
      assertThat(statusOf(as(session, write.getValue())))
          .as("%s in a workspace the caller only reads", write.getKey())
          .isEqualTo(403);
    }
    assertThat(
            statusOf(
                as(session, get("/api/v2/workspace/" + other + "/workflow/" + OTHER_WORKFLOW))))
        .as("a read in the workspace the caller reads")
        .isEqualTo(200);
    assertThat(statusOf(as(session, createWorkflow(home, "grant-scope-home-wf"))))
        .as("a write in the workspace the caller owns")
        .isEqualTo(200);
    assertThat(statusOf(as(session, json(post("/api/v2/token"), keyToken(home)))))
        .as("a token for the workspace the caller owns")
        .isEqualTo(200);
  }

  @Test
  void anEditorOfOneWorkspaceIsRefusedEverythingInAnother() throws Exception {
    String email = email();
    String session = signIn(email);
    addMember(home, email, "editor");

    List<MockHttpServletRequestBuilder> inOther =
        List.of(
            get("/api/v2/workspace/" + other),
            get("/api/v2/workspace/" + other + "/workflow/query"),
            get("/api/v2/workspace/" + other + "/workflow/" + OTHER_WORKFLOW),
            createWorkflow(other, "grant-scope-editor-wf"));
    for (MockHttpServletRequestBuilder request : inOther) {
      assertThat(statusOf(as(session, request))).isEqualTo(403);
    }
    assertThat(statusOf(as(session, createWorkflow(home, "grant-scope-editor-home-wf"))))
        .isEqualTo(200);
  }

  @Test
  void anAdminAndAGlobalTokenWriteInAnyWorkspace() throws Exception {
    String email = email();
    String session = signIn(email);
    UserEntity admin = userRepository.findByEmail(email);
    admin.setType(UserType.admin);
    admin.setStatus(UserStatus.active);
    userRepository.save(admin);
    String global = mint(AuthScope.global, null, List.of("**/**"));

    assertThat(statusOf(as(session, createWorkflow(other, "grant-scope-admin-wf"))))
        .isEqualTo(200);
    assertThat(statusOf(bearer(global, createWorkflow(other, "grant-scope-global-wf"))))
        .isEqualTo(200);
  }

  @Test
  void aWorkspaceKeyTokenActsOnlyInItsWorkspace() throws Exception {
    String key = mint(AuthScope.key, home, List.of("workflow/**"));

    assertThat(statusOf(bearer(key, createWorkflow(other, "grant-scope-key-other-wf"))))
        .isEqualTo(403);
    assertThat(statusOf(bearer(key, createWorkflow(home, "grant-scope-key-home-wf"))))
        .isEqualTo(200);
  }

  @Test
  void anotherWorkspacesRunIsNotActedOnFromOnesOwnOrByIdAlone() throws Exception {
    String email = email();
    String session = signIn(email);
    addMember(home, email, "owner");
    addMember(other, email, "reader");
    String runId =
        asAdmin(
            () ->
                workflowService
                    .submit(other, OTHER_WORKFLOW, new WorkflowSubmitRequest(), false)
                    .getId());

    assertThat(
            statusOf(
                as(
                    session,
                    delete("/api/v2/workspace/" + other + "/workflowrun/" + runId + "/cancel"))))
        .as("cancel through the run's own workspace")
        .isEqualTo(403);
    assertThat(
            statusOf(
                as(
                    session,
                    delete("/api/v2/workspace/" + home + "/workflowrun/" + runId + "/cancel"))))
        .as("cancel through the caller's own workspace")
        .isEqualTo(404);
    assertThat(
            statusOf(
                as(
                    session,
                    json(
                        post("/api/v2/callback?ref=" + runId + "&topic=grant-scope"),
                        Map.of()))))
        .as("a wait-for-event callback for the run")
        .isEqualTo(404);
    assertThat(
            statusOf(
                as(session, json(post("/api/v2/webhook?ref=" + otherWorkflowId), Map.of()))))
        .as("a webhook naming the workflow by id")
        .isEqualTo(404);
    assertThat(
            statusOf(
                as(
                    session,
                    json(
                        post("/api/v2/webhook?workspace=" + other + "&ref=" + OTHER_WORKFLOW),
                        Map.of()))))
        .as("a webhook naming the workflow in its workspace")
        .isEqualTo(404);
  }

  @Test
  void anotherWorkspacesActionAndScheduleAreNotChangedFromOnesOwn() throws Exception {
    String email = email();
    String session = signIn(email);
    addMember(home, email, "owner");
    addMember(other, email, "reader");
    String homeWorkflow = "grant-scope-home-sched-wf";
    asAdmin(() -> workflowService.create(home, runnableWorkflow(homeWorkflow, TASK_SLUG)));
    String scheduleId = asAdmin(() -> scheduleService.create(other, schedule(OTHER_WORKFLOW)).getId());
    String actionId = manualAction(otherWorkflowId);

    WorkflowSchedule moved = schedule(homeWorkflow);
    moved.setId(scheduleId);
    assertThat(statusOf(as(session, json(put("/api/v2/workspace/" + home + "/schedule"), moved))))
        .as("the other workspace's schedule, sent to the caller's own")
        .isEqualTo(404);
    assertThat(statusOf(as(session, get("/api/v2/workspace/" + home + "/action/" + actionId))))
        .as("the other workspace's action, read through the caller's own")
        .isEqualTo(404);
    assertThat(
            statusOf(
                as(
                    session,
                    json(
                        put("/api/v2/workspace/" + home + "/action"),
                        List.of(Map.of("id", actionId, "approved", true))))))
        .as("the other workspace's action, actioned through the caller's own")
        .isEqualTo(404);
    assertThat(actionRepository.findById(actionId).orElseThrow().getStatus())
        .isEqualTo(ActionStatus.submitted);
  }

  @Test
  void anotherWorkspacesTokensAreNeitherListedNorRevoked() throws Exception {
    String email = email();
    String session = signIn(email);
    addMember(home, email, "owner");
    String otherKey = asAdmin(() -> tokenService.create(keyToken(other)).getId());
    String bystander = signInAndGetId(email());

    assertThat(statusOf(as(session, delete("/api/v2/token/" + otherKey))))
        .as("revoking another workspace's token answers as a missing one")
        .isEqualTo(400);
    String listed =
        as(session, get("/api/v2/token/query?types=key&principals=" + other))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(listed).doesNotContain(otherKey);
    TokenCreateRequest personal = new TokenCreateRequest();
    personal.setType(AuthScope.user);
    personal.setName("someone-elses");
    personal.setPrincipal(bystander);
    assertThat(statusOf(as(session, json(post("/api/v2/token"), personal))))
        .as("a personal token for another user")
        .isEqualTo(403);
  }

  @Test
  void aWorkspaceOwnerDoesNotChangeThePlatform() throws Exception {
    String email = email();
    String session = signIn(email);
    addMember(home, email, "owner");

    assertThat(
            statusOf(
                as(
                    session,
                    patch("/api/v2/settings").contentType(MediaType.APPLICATION_JSON).content("{}"))))
        .as("the settings")
        .isEqualTo(401);
    assertThat(
            statusOf(
                as(
                    session,
                    json(post("/api/v2/parameters"), Map.of("name", "grantScopeParam", "type", "string")))))
        .as("a global parameter")
        .isEqualTo(401);
    assertThat(statusOf(as(session, delete("/api/v2/task/" + TASK_SLUG))))
        .as("the global task catalogue")
        .isEqualTo(401);
    String global = mint(AuthScope.global, null, List.of("**/**"));
    assertThat(
            statusOf(
                bearer(
                    global,
                    patch("/api/v2/settings").contentType(MediaType.APPLICATION_JSON).content("{}"))))
        .isEqualTo(200);
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String signIn(String email) {
    try {
      return tokenService.createSessionTokenWithRaw(email, "Grant", "Scope", false, true).rawToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private String signInAndGetId(String email) {
    signIn(email);
    return userRepository.findByEmail(email).getId();
  }

  private int statusOf(ResultActions result) {
    return result.andReturn().getResponse().getStatus();
  }

  private ResultActions as(
      String session, MockHttpServletRequestBuilder request) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request.cookie(new Cookie(SessionCookie.NAME, session)));
  }

  private ResultActions bearer(
      String token, MockHttpServletRequestBuilder request) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request.header("Authorization", "Bearer " + token));
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    return request
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private MockHttpServletRequestBuilder createWorkflow(String workspace, String name)
      throws Exception {
    return json(
        post("/api/v2/workspace/" + workspace + "/workflow"),
        runnableWorkflow(name + "-" + UUID.randomUUID().toString().substring(0, 8), TASK_SLUG));
  }

  private static TokenCreateRequest keyToken(String workspace) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.key);
    request.setName("grant-scope-key");
    request.setPrincipal(workspace);
    request.setPermissions(List.of("workflow/read"));
    return request;
  }

  /** Mints a token as an administrator and returns its raw value. */
  private String mint(AuthScope type, String principal, List<String> permissions) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(type);
    request.setName("grant-scope-" + type);
    request.setPrincipal(principal);
    request.setPermissions(permissions);
    return asAdmin(() -> tokenService.create(request).getToken());
  }

  private static WorkflowSchedule schedule(String workflow) {
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setName("grant-scope-schedule");
    schedule.setWorkflowRef(workflow);
    schedule.setType(WorkflowScheduleType.runOnce);
    schedule.setStatus(WorkflowScheduleStatus.inactive);
    schedule.setDateSchedule(new Date(System.currentTimeMillis() + 86_400_000L));
    schedule.setTimezone("UTC");
    return schedule;
  }

  private String manualAction(String workflowRef) {
    TaskRunEntity taskRun = new TaskRunEntity();
    taskRun.setWorkflowRunRef("no-such-workflowrun");
    taskRun.setName("grant-scope-approval-" + System.nanoTime());
    ActionEntity action = new ActionEntity();
    action.setWorkflowRef(workflowRef);
    action.setType(ActionType.manual);
    action.setStatus(ActionStatus.submitted);
    action.setCreationDate(new Date());
    action.setTaskRunRef(taskRunRepository.save(taskRun).getId());
    return actionRepository.save(action).getId();
  }

  private String workspace() {
    String name = "grant-scope-ws-" + UUID.randomUUID().toString().substring(0, 8);
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(name);
          request.setDisplayName(name);
          return workspaceService.create(request);
        });
    return name;
  }

  private void addMember(String workspace, String email, String role) {
    asAdmin(
        () -> {
          WorkspaceMember member = new WorkspaceMember();
          member.setEmail(email);
          member.setRole(role);
          WorkspaceRequest request = new WorkspaceRequest();
          request.setMembers(List.of(member));
          return workspaceService.patch(workspace, request);
        });
  }

  /** Runs the call as a global caller, then leaves no identity behind for the next request. */
  private <T> T asAdmin(Supplier<T> call) {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("grant-scope-admin");
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
    return "grant-scope-" + UUID.randomUUID() + "@example.test";
  }
}
