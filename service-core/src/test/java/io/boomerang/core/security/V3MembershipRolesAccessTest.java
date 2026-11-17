package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.core.TokenService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.migration.SeedResources;
import io.boomerang.migration._0050__V3MembershipRoles;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * A plain user upgraded from v3 works in their personal workspace and their team's. The v3
 * relationship graph build writes their memberships without a role; through the real filter chain,
 * such a membership reads as {@code reader}, and once {@link _0050__V3MembershipRoles} has run the
 * personal workspace grants {@code owner} and the team {@code editor}.
 */
class V3MembershipRolesAccessTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "v3-membership-roles-task";

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private UserRepository userRepository;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private Environment environment;
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

  @Test
  void anUpgradedPlainUserOwnsTheirPersonalWorkspaceAndEditsTheirTeams() throws Exception {
    String email = "v3-member-" + UUID.randomUUID() + "@example.test";
    String session = signIn(email);
    UserEntity user = userRepository.findByEmail(email);
    String personal = workspace();
    String team = workspace();
    String personalId = workspaceId(personal);
    String teamId = workspaceId(team);
    // The shapes the v3 units leave: the personal workspace typed and linked to its user, and a
    // membership edge of each with no role.
    workspaces()
        .updateOne(
            Filters.eq("_id", new ObjectId(personalId)),
            Updates.combine(Updates.set("type", "personal"), Updates.set("externalRef", user.getId())));
    for (String workspaceId : List.of(personalId, teamId)) {
      edges()
          .insertOne(
              SeedResources.edge(
                  "user:" + user.getId(), "memberOf", "workspace:" + workspaceId, new Document()));
    }

    // A membership without a role reads as reader: the workspace is read, nothing is written.
    assertThat(grants(user)).containsEntry(personalId, List.of("**/read")).containsEntry(teamId, List.of("**/read"));
    for (String workspace : List.of(personal, team)) {
      as(session, get("/api/v2/workspace/" + workspace + "/workflow/query")).andExpect(status().isOk());
      as(session, createWorkflow(workspace, "v3-member-before-wf")).andExpect(status().isForbidden());
    }

    new _0050__V3MembershipRoles().execute(mongoTemplate.getDb(), environment);

    assertThat(relationshipService.roles(user.getId()))
        .containsEntry(personalId, "owner")
        .containsEntry(teamId, "editor");
    assertThat(grants(user))
        .containsEntry(personalId, List.of("**/**"))
        .containsEntry(teamId, List.of("**/read", "**/write", "**/action"));
    for (String workspace : List.of(personal, team)) {
      as(session, createWorkflow(workspace, "v3-member-after-wf")).andExpect(status().isOk());
      as(session, get("/api/v2/workspace/" + workspace + "/workflow/v3-member-after-wf"))
          .andExpect(status().isOk());
    }
  }

  // The user's workspace grants, as the workspace id -> permissions.
  private Map<String, List<String>> grants(UserEntity user) {
    return tokenService.resolvePermissionsForUser(user).stream()
        .filter(grant -> grant.getScope() == PermissionScope.workspace)
        .collect(Collectors.toMap(ResolvedPermissions::getPrincipal, ResolvedPermissions::getActions));
  }

  private String workspaceId(String name) {
    return workspaces().find(Filters.eq("name", name)).first().get("_id").toString();
  }

  private MongoCollection<Document> workspaces() {
    return mongoTemplate.getCollection("flowtest_workspaces");
  }

  private MongoCollection<Document> edges() {
    return mongoTemplate.getCollection("flowtest_rel_edges");
  }

  /** Signs the person in as the webapp's exchange does and returns the session cookie's value. */
  private String signIn(String email) {
    try {
      return tokenService.createSessionTokenWithRaw(email, "V3", "Member", false, true).rawToken();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private ResultActions as(String session, MockHttpServletRequestBuilder request) throws Exception {
    SecurityContextHolder.clearContext();
    return mockMvc.perform(request.cookie(new Cookie(SessionCookie.NAME, session)));
  }

  private MockHttpServletRequestBuilder createWorkflow(String workspace, String name) throws Exception {
    return post("/api/v2/workspace/" + workspace + "/workflow")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(runnableWorkflow(name, TASK_SLUG)));
  }

  /** Creates a workspace as an administrator, with no members. */
  private String workspace() {
    String name = "v3-member-ws-" + UUID.randomUUID().toString().substring(0, 8);
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("v3-membership-admin");
    admin.setPermissions(List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(admin.getPrincipal(), null);
    authentication.setDetails(admin);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      WorkspaceRequest request = new WorkspaceRequest();
      request.setName(name);
      request.setDisplayName(name);
      workspaceService.create(request);
    } finally {
      SecurityContextHolder.clearContext();
    }
    return name;
  }
}
