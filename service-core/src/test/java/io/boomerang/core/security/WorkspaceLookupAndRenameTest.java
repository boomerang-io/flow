package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.boomerang.core.TokenService;
import io.boomerang.core.audit.AuditWorkspace;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.repository.TokenRepository;
import io.boomerang.core.repository.UserRepository;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
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
 * A workspace is read and patched by its id or its name, by a member and a global token alike. A
 * rename moves the workspace to the new name; a new name, like a created one, is lower-case
 * letters, numbers and hyphens and never shaped like an id, else it is refused; the system
 * workspace keeps its name. A rename addressed by id touches that workspace alone, even beside one
 * of the same name. A token issued for the name follows a rename to the id, so it is listed and
 * revoked with the workspace and never passes to the next holder of the name, and a key token works
 * on the workspace by id or by name whichever its grants name.
 */
class WorkspaceLookupAndRenameTest extends AbstractEngineIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private TokenService tokenService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TokenRepository tokenRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
  @Autowired private io.boomerang.core.audit.AuditEventEmitter auditEventEmitter;

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
    anchor.setEmail("workspace-lookup-anchor-" + UUID.randomUUID() + "@example.test");
    anchor.setName("anchor");
    userRepository.save(anchor);
    workspace = workspace();
  }

  @Test
  void aMemberAndAGlobalTokenReadAndPatchByIdOrName() throws Exception {
    String id = idOf(workspace);
    String editor = signedInMember("editor");
    String global = mint(List.of("**/**"));

    for (String key : List.of(id, workspace)) {
      MvcResult read = as(editor, get("/api/v2/workspace/" + key));
      assertThat(read.getResponse().getStatus()).as("a member reads by " + key).isEqualTo(200);
      assertThat(nameIn(read)).isEqualTo(workspace);
      assertThat(bearer(global, get("/api/v2/workspace/" + key)).getResponse().getStatus())
          .as("a global token reads by " + key)
          .isEqualTo(200);
      MvcResult patched =
          as(editor, json(patch("/api/v2/workspace/" + key), Map.of("displayName", "By " + key)));
      assertThat(patched.getResponse().getStatus()).as("a member patches by " + key).isEqualTo(200);
      assertThat(bearer(global, json(patch("/api/v2/workspace/" + key), Map.of("displayName", "Global " + key)))
              .getResponse()
              .getStatus())
          .as("a global token patches by " + key)
          .isEqualTo(200);
      assertThat(workspaceRepository.findById(id).orElseThrow().getDisplayName()).isEqualTo("Global " + key);
    }
  }

  @Test
  void aRenameMovesTheWorkspaceToTheNewName() throws Exception {
    String id = idOf(workspace);
    String global = mint(List.of("**/**"));
    String renamed = workspace + "-renamed";

    MvcResult rename = bearer(global, json(patch(path()), Map.of("name", renamed)));

    assertThat(rename.getResponse().getStatus()).isEqualTo(200);
    assertThat(nameIn(rename)).isEqualTo(renamed);
    assertThat(workspaceRepository.findById(id).orElseThrow().getName()).isEqualTo(renamed);
    assertThat(relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, id)).isEqualTo(renamed);
    assertThat(bearer(global, get("/api/v2/workspace/" + renamed)).getResponse().getStatus()).isEqualTo(200);
    assertThat(bearer(global, json(patch("/api/v2/workspace/" + renamed), Map.of("name", renamed)))
            .getResponse()
            .getStatus())
        .as("the stored name sent back renames nothing")
        .isEqualTo(200);
  }

  @Test
  void aNameNotInItsCanonicalFormIsRefusedOnCreateAndOnRename() throws Exception {
    String global = mint(List.of("**/**"));

    MvcResult created =
        bearer(
            global,
            json(post("/api/v2/workspace"), Map.of("name", "Platform Team", "displayName", "Platform Team")));
    MvcResult renamed = bearer(global, json(patch(path()), Map.of("name", "Platform_Team")));

    assertThat(created.getResponse().getStatus()).isEqualTo(400);
    assertThat(reasonOf(created)).isEqualTo("WORKSPACE_NAME_NOT_CANONICAL");
    assertThat(messageOf(created)).contains("\"platform-team\"");
    assertThat(workspaceRepository.findByNameIgnoreCase("Platform Team")).isEmpty();
    assertThat(renamed.getResponse().getStatus()).isEqualTo(400);
    assertThat(reasonOf(renamed)).isEqualTo("WORKSPACE_NAME_NOT_CANONICAL");
    assertThat(workspaceRepository.findByNameIgnoreCase(workspace)).isPresent();
  }

  // A token issued for the name follows the workspace through a rename to its id, is listed and
  // revoked with it, and never wakes for the next workspace to take the old name.
  @Test
  void aTokenIssuedForTheNameFollowsARenameAndNeverPassesToTheNextHolderOfTheName() throws Exception {
    String id = idOf(workspace);
    String byId = mintKey(id);
    String byName = mintKey(workspace);
    String moved = workspace + "-moved";
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(moved);
          return workspaceService.patch(workspace, request);
        });

    assertThat(bearer(byId, get("/api/v2/workspace/" + moved)).getResponse().getStatus())
        .as("issued for the id")
        .isEqualTo(200);
    assertThat(bearer(byName, get("/api/v2/workspace/" + moved)).getResponse().getStatus())
        .as("issued for the old name, now the id's")
        .isEqualTo(200);
    assertThat(keyTokenPrincipals(moved)).containsExactly(id, id);

    asAdmin(
        () -> {
          workspaceService.delete(moved);
          return null;
        });
    assertThat(tokenRepository.findAll().stream().map(TokenEntity::getPrincipal))
        .doesNotContain(id, workspace, moved);

    String reused = workspace;
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(reused);
          request.setDisplayName(reused);
          return workspaceService.create(request);
        });
    assertThat(bearer(byName, get("/api/v2/workspace/" + reused)).getResponse().getStatus())
        .as("the next workspace of the old name")
        .isIn(401, 403);
  }

  // Two workspaces holding one name, as a 3.5 upgrade can carry over: a change addressed by one's
  // id touches that one alone, its graph node and its members.
  @Test
  void aRenameByIdOfOneOfTwoSameNamedWorkspacesLeavesTheOtherAlone() throws Exception {
    String other = workspace();
    String otherId = idOf(other);
    String id = idOf(workspace);
    sameName(id, other);
    String global = mint(List.of("**/**"));

    // Members first, while the two still share the name, then the rename.
    MvcResult joined =
        bearer(
            global,
            json(
                patch("/api/v2/workspace/" + id),
                Map.of("members", List.of(Map.of("email", email(), "role", "reader")))));
    MvcResult renamed = bearer(global, json(patch("/api/v2/workspace/" + id), Map.of("name", other + "-b")));

    assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
    assertThat(joined.getResponse().getStatus()).isEqualTo(200);
    assertThat(workspaceRepository.findById(id).orElseThrow().getName()).isEqualTo(other + "-b");
    assertThat(relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, id)).isEqualTo(other + "-b");
    assertThat(workspaceRepository.findById(otherId).orElseThrow().getName()).isEqualTo(other);
    assertThat(relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, otherId)).isEqualTo(other);
    assertThat(relationshipService.membersAndRoles(otherId)).doesNotContainValue("reader");
    assertThat(relationshipService.membersAndRoles(id)).containsValue("reader");
  }

  // A key token whose grants name the workspace by id or by name works on paths naming it either
  // way, for grants that name no workspace action.
  @Test
  void aNarrowKeyTokenWorksOnTheWorkspaceByIdAndByName() throws Exception {
    asAdmin(
        () -> {
          seedGlobalTask("workspace-lookup-task");
          return null;
        });
    String id = idOf(workspace);
    for (String principal : List.of(id, workspace)) {
      String token = mintKey(principal, List.of("workflow/write", "workflow/read"));
      for (String key : List.of(id, workspace)) {
        MvcResult created =
            bearer(
                token,
                json(
                    post("/api/v2/workspace/" + key + "/workflow"),
                    runnableWorkflow(
                        "narrow-" + UUID.randomUUID().toString().substring(0, 8), "workspace-lookup-task")));
        assertThat(created.getResponse().getStatus())
            .as("issued for " + principal + ", addressed by " + key)
            .isEqualTo(200);
      }
    }
  }

  // A name two workspaces share names neither: the trail records it as given, and the listing asks
  // for an id instead of guessing.
  @Test
  void aNameTwoWorkspacesShareIsRecordedAsGivenAndRefusedAsAListingFilter() throws Exception {
    String other = workspace();
    String otherId = idOf(other);
    sameName(idOf(workspace), other);
    String global = mint(List.of("**/**"));

    assertThat(auditEventEmitter.workspace(other)).isEqualTo(new AuditWorkspace(other, other));
    MvcResult listed = bearer(global, get("/api/v2/audit").param("workspaceId", other));

    assertThat(listed.getResponse().getStatus()).isEqualTo(400);
    assertThat(reasonOf(listed)).isEqualTo("QUERY_INVALID_FILTERS");
    assertThat(bearer(global, get("/api/v2/audit").param("workspaceId", otherId)).getResponse().getStatus())
        .isEqualTo(200);
  }

  @Test
  void theSystemWorkspaceAndANameShapedLikeAnIdAreRefused() throws Exception {
    String global = mint(List.of("**/**"));
    String idShaped = new org.bson.types.ObjectId().toHexString();

    MvcResult system = bearer(global, json(patch("/api/v2/workspace/system"), Map.of("name", "system-renamed")));
    MvcResult renamed = bearer(global, json(patch(path()), Map.of("name", idShaped)));
    MvcResult created =
        bearer(global, json(post("/api/v2/workspace"), Map.of("name", idShaped, "displayName", "Shaped")));

    assertThat(system.getResponse().getStatus()).isEqualTo(400);
    assertThat(reasonOf(system)).isEqualTo("WORKSPACE_NAME_RESERVED");
    assertThat(workspaceRepository.findByNameIgnoreCase("system")).isPresent();
    assertThat(reasonOf(renamed)).isEqualTo("WORKSPACE_NAME_RESERVED");
    assertThat(reasonOf(created)).isEqualTo("WORKSPACE_NAME_RESERVED");
  }

  // A workspace is reached through the graph node the access check reads: a name equal to another
  // workspace's id, which only a database written by hand could hold, reaches neither.
  @Test
  void theAccessCheckAndTheLookupReadTheSameWorkspace() throws Exception {
    String victim = workspace();
    String victimId = idOf(victim);
    String editor = signedInMember("editor");
    sameName(idOf(workspace), victimId);

    MvcResult read = as(editor, get("/api/v2/workspace/" + victimId));
    MvcResult patched =
        as(editor, json(patch("/api/v2/workspace/" + victimId), Map.of("displayName", "Not yours")));

    assertThat(read.getResponse().getStatus()).isNotEqualTo(200);
    assertThat(patched.getResponse().getStatus()).isNotEqualTo(200);
    assertThat(workspaceRepository.findById(victimId).orElseThrow().getDisplayName()).isEqualTo(victim);
  }

  @Test
  void aWorkspaceIsDeletedByItsId() throws Exception {
    String id = idOf(workspace);
    String global = mint(List.of("**/**"));

    assertThat(bearer(global, delete("/api/v2/workspace/" + id)).getResponse().getStatus()).isEqualTo(200);
    assertThat(workspaceRepository.findById(id)).isEmpty();
  }

  // Gives the workspace of the id the name, in its record and its graph node.
  private void sameName(String id, String name) {
    mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(io.boomerang.workspace.entity.WorkspaceEntity.class))
        .updateOne(
            new org.bson.Document("_id", new org.bson.types.ObjectId(id)),
            new org.bson.Document("$set", new org.bson.Document("name", name)));
    long moved =
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(io.boomerang.core.entity.RelationshipNodeEntity.class))
            .updateOne(
                new org.bson.Document("type", "workspace").append("ref", id),
                new org.bson.Document("$set", new org.bson.Document("slug", name)))
            .getModifiedCount();
    assertThat(moved).as("the workspace's graph node takes the name").isEqualTo(1);
    // The test's request memo holds the nodes as they were; the next request reads them afresh.
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }

  private List<String> keyTokenPrincipals(String workspace) {
    return asAdmin(
        () ->
            tokenService
                .query(
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    java.util.Optional.of(List.of(AuthScope.key)),
                    java.util.Optional.of(List.of(workspace)))
                .getContent()
                .stream()
                .map(Token::getPrincipal)
                .toList());
  }

  @Test
  void theTokenListFindsTokensIssuedForTheIdOrTheNameAndDeleteRevokesBoth() {
    String id = idOf(workspace);
    mintKey(id);
    mintKey(workspace);

    List<String> listed =
        asAdmin(
            () ->
                tokenService
                    .query(
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.of(List.of(AuthScope.key)),
                        java.util.Optional.of(List.of(workspace)))
                    .getContent()
                    .stream()
                    .map(Token::getPrincipal)
                    .toList());
    assertThat(listed).containsExactlyInAnyOrder(id, workspace);

    asAdmin(
        () -> {
          workspaceService.delete(workspace);
          return null;
        });

    assertThat(tokenRepository.findAll().stream().map(TokenEntity::getPrincipal))
        .doesNotContain(id, workspace);
  }

  private String nameIn(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("name").asString();
  }

  private String messageOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asString();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String path() {
    return "/api/v2/workspace/" + workspace;
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
      return tokenService.createSessionTokenWithRaw(email, "Workspace", "Lookup", false, true).rawToken();
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
    request.setName("workspace-lookup-global");
    request.setPermissions(permissions);
    return asAdmin(() -> tokenService.create(request).getToken());
  }

  private String workspace() {
    String name = "workspace-lookup-" + UUID.randomUUID().toString().substring(0, 8);
    asAdmin(
        () -> {
          WorkspaceRequest request = new WorkspaceRequest();
          request.setName(name);
          request.setDisplayName(name);
          return workspaceService.create(request);
        });
    return name;
  }

  private String idOf(String name) {
    return workspaceRepository.findByNameIgnoreCase(name).orElseThrow().getId();
  }

  private String mintKey(String principal) {
    return mintKey(principal, List.of("workspace/read", "workspace/write"));
  }

  private String mintKey(String principal, List<String> permissions) {
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.key);
    request.setName("workspace-lookup-key");
    request.setPrincipal(principal);
    request.setPermissions(permissions);
    return asAdmin(() -> tokenService.create(request).getToken());
  }

  /** Runs the call as a global caller, then leaves no identity behind for the next request. */
  private <T> T asAdmin(Supplier<T> call) {
    Token admin = new Token(AuthScope.global);
    admin.setPrincipal("workspace-lookup-admin");
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
    return "workspace-lookup-" + UUID.randomUUID() + "@example.test";
  }
}
