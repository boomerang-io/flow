package io.boomerang.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.config.MongoConfiguration;
import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.UnauthenticatedGlobalToken;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the direct-query RelationshipService against a real MongoDB (Testcontainers),
 * wiring only the relationship repositories - no full application context.
 *
 * <p>Fixture: root -> workspace:t1(acme) + workspace:t2(other-team) + task:task1(sleep);
 * user:u1 member of t1, user:u2 member of t2; t1 has workflow:w1(build-app);
 * t2 has workflow:w2(deploy-app) and workflow:w3(acme) - a cross-type slug collision with t1.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RelationshipServiceTest.MongoTestConfig.class)
@TestPropertySource(properties = "flow.mongo.collection.prefix=flowtest")
class RelationshipServiceTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  static {
    MONGO.start();
  }

  @Configuration
  @EnableMongoRepositories(
      basePackageClasses = RelationshipNodeRepository.class,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {RelationshipNodeRepository.class, RelationshipEdgeRepository.class}))
  static class MongoTestConfig {

    @Bean
    static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
      return new PropertySourcesPlaceholderConfigurer();
    }

    // Registered under the exact name the entity @Document SpEL references.
    @Bean
    MongoConfiguration mongoConfiguration() {
      return new MongoConfiguration();
    }

    @Bean
    MongoDatabaseFactory mongoDatabaseFactory() {
      return new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl("boomerang"));
    }

    // Container-managed mapping context so the entity @Document SpEL can resolve the
    // mongoConfiguration bean for prefixed collection names.
    @Bean
    MongoMappingContext mongoMappingContext() {
      return new MongoMappingContext();
    }

    @Bean
    MappingMongoConverter mappingMongoConverter(
        MongoDatabaseFactory mongoDatabaseFactory, MongoMappingContext mongoMappingContext) {
      return new MappingMongoConverter(
          new DefaultDbRefResolver(mongoDatabaseFactory), mongoMappingContext);
    }

    @Bean
    MongoTemplate mongoTemplate(
        MongoDatabaseFactory mongoDatabaseFactory, MappingMongoConverter mappingMongoConverter) {
      return new MongoTemplate(mongoDatabaseFactory, mappingMongoConverter);
    }
  }

  @Autowired private RelationshipNodeRepository nodeRepository;
  @Autowired private RelationshipEdgeRepository edgeRepository;

  private IdentityService identityService;
  private RelationshipService service;
  private RelationshipService secondService;

  @BeforeEach
  void setUp() {
    nodeRepository.deleteAll();
    edgeRepository.deleteAll();

    identityService = mock(IdentityService.class);
    service =
        new RelationshipService(
            nodeRepository, edgeRepository, identityService, new SimpleMeterRegistry());
    secondService =
        new RelationshipService(
            nodeRepository, edgeRepository, identityService, new SimpleMeterRegistry());

    node("root", "root", "root");
    node("workspace", "t1", "acme");
    node("workspace", "t2", "other-team");
    node("user", "u1", "u1@example.com");
    node("user", "u2", "u2@example.com");
    node("workflow", "w1", "build-app");
    node("workflow", "w2", "deploy-app");
    node("workflow", "w3", "acme");
    node("task", "task1", "sleep");

    edge("root:root", RelationshipLabel.CONTAINS, "workspace:t1", Map.of());
    edge("root:root", RelationshipLabel.CONTAINS, "workspace:t2", Map.of());
    edge("root:root", RelationshipLabel.HAS_TASK, "task:task1", Map.of());
    edge("user:u1", RelationshipLabel.MEMBER_OF, "workspace:t1", Map.of("role", "owner"));
    edge("user:u2", RelationshipLabel.MEMBER_OF, "workspace:t2", Map.of("role", "editor"));
    edge("workspace:t1", RelationshipLabel.HAS_WORKFLOW, "workflow:w1", Map.of());
    edge("workspace:t2", RelationshipLabel.HAS_WORKFLOW, "workflow:w2", Map.of());
    edge("workspace:t2", RelationshipLabel.HAS_WORKFLOW, "workflow:w3", Map.of());
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void node(String type, String ref, String slug) {
    nodeRepository.save(new RelationshipNodeEntity(type, ref, slug, Optional.empty()));
  }

  private void edge(String from, RelationshipLabel label, String to, Map<String, String> data) {
    edgeRepository.save(new RelationshipEdgeEntity(from, label, to, Optional.of(data)));
  }

  // u1 is seeded MEMBER_OF t1 with role "owner"; u2 is seeded MEMBER_OF t2 with role "editor"
  // (see setUp()). Real workspace-role permission shape per TokenService.resolvePermissionsForUser
  // + seed/roles.json: "owner" -> ["**/**"], "editor" -> ["**/read","**/write","**/action"].
  private static final Map<String, String> USER_WORKSPACE = Map.of("u1", "t1", "u2", "t2");

  /**
   * Grants a full-access ("**&#47;**") workspace-scoped permission regardless of the seeded role,
   * i.e. both u1 (owner) and u2 (editor) get an owner-shaped grant here.
   *
   * <p>This is deliberately NOT what a real editor-role session token carries. It is a Rule-3
   * finding (see the task report): {@code RelationshipService.checkPermissions()}
   * (RelationshipService.java:425-437) only bypasses on a literal {@code "**&#47;**"} or {@code
   * "<type>/**"} action string, or on an exact match between the grant's {@code principal} (a
   * workspace id) and the checked {@code toList}. A real "editor"/"reader" role grant
   * (["**&#47;read","**&#47;write","**&#47;action"], principal=workspaceId) satisfies NEITHER
   * condition for any {@code RelationshipType} other than WORKSPACE-checking-itself, so under now
   * -enforced {@code check()}, a real editor/reader is denied for WORKFLOW/WORKFLOWRUN/TASK/etc.
   * checks - e.g. every {@code relationshipService.check(RelationshipType.WORKFLOW, ...)} call in
   * {@code ActionService}/{@code WebhookEventService} and every WORKFLOWRUN check in {@code
   * WorkflowRunService}. This suite exists to test the relationship WALK (containment/type-scoping),
   * which is orthogonal to that gap, so it uses a full-access grant to isolate it - not to claim
   * editor/reader tokens work today.
   */
  private void asUser(String ref) {
    Token token = new Token(AuthScope.session);
    token.setPrincipal(ref);
    token.setPermissions(
        List.of(
            new ResolvedPermissions(
                PermissionScope.workspace, USER_WORKSPACE.get(ref), List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);
  }

  @Test
  @DisplayName("A team member sees the team's workflows via filter(); a non-member does not")
  void memberSeesOwnTeamWorkflowsOnly() {
    asUser("u1");
    assertEquals(
        List.of("w1"),
        service.filter(
            RelationshipType.WORKFLOW, Optional.empty(), Optional.empty(), Optional.empty(),
            false));
    assertEquals(
        List.of("w1"),
        service.filter(
            RelationshipType.WORKFLOW,
            Optional.empty(),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of("acme")),
            false));
    assertTrue(
        service.check(RelationshipType.WORKFLOW, "w1", Optional.empty(), Optional.empty()));

    asUser("u2");
    assertEquals(
        List.of("w2", "w3"),
        service
            .filter(
                RelationshipType.WORKFLOW, Optional.empty(), Optional.empty(), Optional.empty(),
                false)
            .stream()
            .sorted()
            .toList());
    assertFalse(
        service.check(RelationshipType.WORKFLOW, "w1", Optional.empty(), Optional.empty()));
  }

  @Test
  @DisplayName("Slug lookups are type-scoped despite a cross-type slug collision")
  void slugResolutionIsTypeScoped() {
    // "acme" exists as a team slug and a workflow slug, but for no other type.
    assertTrue(service.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, "acme"));
    assertTrue(service.doesSlugOrRefExistForType(RelationshipType.WORKFLOW, "acme"));
    assertFalse(service.doesSlugOrRefExistForType(RelationshipType.SCHEDULE, "acme"));
    assertEquals("acme", service.getSlugByRefForType(RelationshipType.WORKFLOW, "w3"));

    // The workflow named "acme" belongs to t2: t1's member must not gain access through
    // the identically-named team.
    asUser("u1");
    assertTrue(service.check(RelationshipType.WORKSPACE, "acme", Optional.empty(), Optional.empty()));
    assertFalse(
        service.check(RelationshipType.WORKFLOW, "acme", Optional.empty(), Optional.empty()));
    asUser("u2");
    assertTrue(
        service.check(RelationshipType.WORKFLOW, "acme", Optional.empty(), Optional.empty()));
  }

  @Test
  @DisplayName("A mutation through one service instance is immediately visible to another")
  void mutationThroughOneInstanceVisibleToAnother() {
    asUser("u2");
    assertFalse(
        secondService.check(RelationshipType.WORKFLOW, "w1", Optional.empty(), Optional.empty()));

    service.createEdge(
        RelationshipType.USER,
        "u2",
        RelationshipLabel.MEMBER_OF,
        RelationshipType.WORKSPACE,
        "t1",
        Optional.of(Map.of("role", "viewer")));

    assertTrue(
        secondService.check(RelationshipType.WORKFLOW, "w1", Optional.empty(), Optional.empty()));
    assertEquals(Map.of("u1", "owner", "u2", "viewer"), secondService.membersAndRoles("t1"));
  }

  @Test
  @DisplayName("Removing a node cascades to all edges linked to it")
  void removeNodeCascadesEdges() {
    service.removeNodeAndEdgeByRefOrSlug(RelationshipType.WORKFLOW, "w1");

    assertFalse(service.doesSlugOrRefExistForType(RelationshipType.WORKFLOW, "w1"));
    assertTrue(
        edgeRepository
            .findByToAndLabel("workflow:w1", RelationshipLabel.HAS_WORKFLOW.getLabel())
            .isEmpty());
    asUser("u1");
    assertTrue(
        service
            .filter(
                RelationshipType.WORKFLOW, Optional.empty(), Optional.empty(), Optional.empty(),
                false)
            .isEmpty());
  }

  @Test
  @DisplayName("Tasks are a global catalogue visible to every principal")
  void taskCatalogueIsGloballyVisible() {
    // u2 has no membership path to the task node; visibility comes from the catalogue anchor.
    asUser("u2");
    assertEquals(
        List.of("task1"),
        service.filter(
            RelationshipType.TASK, Optional.empty(), Optional.empty(), Optional.empty(), false));
  }

  @Test
  @DisplayName(
      "With security disabled the established UnauthenticatedGlobalToken behaves unscoped, exactly"
          + " as the deleted no-principal branches did (e.g. flow.mode=engine)")
  void securityDisabledIdentityIsUnscopedNotDenied() {
    when(identityService.getCurrentIdentity()).thenReturn(new UnauthenticatedGlobalToken());

    assertTrue(
        service.check(RelationshipType.WORKFLOW, "w1", Optional.empty(), Optional.empty()));
    assertTrue(
        service.check(RelationshipType.WORKFLOW, "w3", Optional.empty(), Optional.empty()));

    assertEquals(
        List.of("w1", "w2", "w3"),
        service
            .filter(
                RelationshipType.WORKFLOW, Optional.empty(), Optional.empty(), Optional.empty(),
                false)
            .stream()
            .sorted()
            .toList());
  }

  @Test
  @DisplayName("The per-request memo never serves a node that a mutation has since changed")
  void requestMemoInvalidatedByMutation() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));

    assertEquals("acme", service.getSlugByRefForType(RelationshipType.WORKSPACE, "t1"));
    service.updateNodeByRefOrSlug(RelationshipType.WORKSPACE, "t1", "renamed");
    assertEquals("renamed", service.getSlugByRefForType(RelationshipType.WORKSPACE, "t1"));
  }

  @Test
  @DisplayName(
      "With no principal, the \"for the current principal\" mutation overloads fail clearly"
          + " (AUTH_REQUIRED) instead of NPE-ing - unlike check()/filter(), there is no \"me\" to"
          + " create/update/remove an edge for")
  void noPrincipalMutationOverloadsFailClearlyNotNpe() {
    when(identityService.getCurrentIdentity()).thenReturn(null);

    assertThrows(
        BoomerangException.class,
        () -> service.createEdge(RelationshipType.WORKSPACE, "t1", Optional.empty()));
    assertThrows(
        BoomerangException.class,
        () -> service.updateEdgeData(RelationshipType.WORKSPACE, "t1", Map.of("role", "owner")));
    assertThrows(
        BoomerangException.class, () -> service.removeEdge(RelationshipType.WORKSPACE, "t1"));

    try {
      service.removeEdge(RelationshipType.WORKSPACE, "t1");
    } catch (BoomerangException ex) {
      assertEquals(BoomerangError.AUTH_REQUIRED.getReason(), ex.getReason());
    }
  }

  /**
   * TRIPWIRE — {@code check()} and {@code filter()} are NOT equivalent for a {@code global}-scoped
   * identity, and the difference is load-bearing for tenant containment.
   *
   * <p>{@code filter()}'s {@code case global} swaps only the ANCHOR to {@code root}; it still runs
   * the walk with {@code intermediateType}/{@code intermediateList} intact, so a
   * workspace-containment constraint is still honoured. {@code check()}'s {@code case global}
   * returns {@code true} BEFORE {@code hasNodes()} is reached, so the intermediate is never
   * evaluated and containment silently evaporates.
   *
   * <p>That asymmetry matters because callers in {@code WorkspaceWorkflowRunService} (lines 58,
   * 174, 195, 214, 235, 254, 273) pass {@code WORKSPACE=[team]} as the intermediate and rely on
   * {@code check()} for tenant containment and existence, not merely for authz narrowing. Six of
   * those seven mutate state.
   *
   * <p><b>This is NOT specific to the security-disabled path.</b> Deleting {@code check()}'s
   * no-principal branch did not close it: the branch returned {@code true} explicitly "mirroring
   * the global token case below", and the established {@code UnauthenticatedGlobalToken} now
   * reaches that very same {@code case global}. The first assertion below is therefore driven by a
   * GENUINE {@code global} token - a real admin/service credential under {@code
   * flow.security.enabled=true} - to pin that the defect is pre-existing and independent of this
   * change. Closing it is a separate maintainer decision about containment semantics for global
   * tokens.
   */
  @Test
  @DisplayName(
      "For a global-scoped token, check() drops the workspace-containment intermediate while"
          + " filter() preserves it - security-enabled and security-disabled alike")
  void globalScopeCheckDropsContainmentButFilterKeepsIt() {
    node("workflowrun", "r1", "r1");
    node("workflowrun", "r2", "r2");
    edge("workspace:t1", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun:r1", Map.of());
    edge("workspace:t2", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun:r2", Map.of());

    // A REAL global token, as minted for an admin/service caller with security ENABLED - per
    // TokenService.resolvePermissionsForUser, an admin/operator UserType always resolves to the
    // "global"/"admin" (or "operator") role's seeded permissions, i.e. a "**/**" grant
    // (seed/roles.json). A grantless global token is a condition production never produces.
    Token globalToken = new Token(AuthScope.global);
    globalToken.setPrincipal("admin-1");
    globalToken.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(globalToken);

    // r2 belongs to workspace t2. Asking "is r2 contained in t1?" must be false, but check()
    // short-circuits to true without ever evaluating the intermediate.
    assertTrue(
        service.check(
            RelationshipType.WORKFLOWRUN,
            "r2",
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of("t1"))),
        "KNOWN GAP: check() returns true for a run in ANOTHER workspace because `case global`"
            + " skips the containment intermediate entirely");

    // The synthetic security-off identity reaches the SAME branch, so it inherits the same gap.
    when(identityService.getCurrentIdentity()).thenReturn(new UnauthenticatedGlobalToken());
    assertTrue(
        service.check(
            RelationshipType.WORKFLOWRUN,
            "r2",
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of("t1"))),
        "the security-off identity inherits the global branch's containment gap unchanged");

    // filter(), given the same containment constraint, correctly excludes r2.
    assertEquals(
        List.of("r1"),
        service.filter(
            RelationshipType.WORKFLOWRUN,
            Optional.of(List.of("r1", "r2")),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of("t1")),
            false),
        "filter() anchors at root but still honours the intermediate, so containment holds");
  }
}
