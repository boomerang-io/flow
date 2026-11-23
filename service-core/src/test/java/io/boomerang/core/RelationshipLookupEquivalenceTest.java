package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The relationship lookups answer exactly what the level-by-level walk over every outgoing edge
 * answers, for every token class, on a graph of the production shape: a user in three workspaces,
 * runs in several workspaces, global and workspace tasks of one name, an integration shared by two
 * workspaces, a template link with its claim edge, a dangling run edge and an orphan run node. The
 * walk is kept here, as it stood before the lookups stopped reading runs, as the oracle.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RelationshipServiceTest.MongoTestConfig.class)
@TestPropertySource(properties = "flow.mongo.collection.prefix=flowequiv")
class RelationshipLookupEquivalenceTest {

  @Autowired private RelationshipNodeRepository nodeRepository;
  @Autowired private RelationshipEdgeRepository edgeRepository;
  @Autowired private MongoTemplate template;

  private IdentityService identityService;
  private RelationshipService service;

  @BeforeEach
  void aGraphOfTheProductionShape() {
    nodeRepository.deleteAll();
    edgeRepository.deleteAll();
    identityService = mock(IdentityService.class);
    service =
        new RelationshipService(
            nodeRepository, edgeRepository, identityService, new SimpleMeterRegistry(), template);

    node("root", "root", "root");
    for (String[] workspace :
        List.of(
            new String[] {"ws1", "alpha"},
            new String[] {"ws2", "beta"},
            new String[] {"ws3", "gamma"},
            new String[] {"ws4", "delta"},
            new String[] {"sys", "system"})) {
      node("workspace", workspace[0], workspace[1]);
      edge("root:root", RelationshipLabel.CONTAINS, "workspace:" + workspace[0]);
    }
    for (String user : List.of("u3", "u1", "u0")) {
      node("user", user, user + "@test.local");
      edge("root:root", RelationshipLabel.CONTAINS, "user:" + user);
    }
    member("u3", "ws1");
    member("u3", "ws2");
    member("u3", "ws3");
    member("u1", "ws1");

    child("ws1", RelationshipLabel.HAS_WORKFLOW, "workflow", "wf1", "build");
    child("ws2", RelationshipLabel.HAS_WORKFLOW, "workflow", "wf2", "build");
    child("ws3", RelationshipLabel.HAS_WORKFLOW, "workflow", "wf3", "alpha");
    child("ws4", RelationshipLabel.HAS_WORKFLOW, "workflow", "wf4", "secret");
    child("sys", RelationshipLabel.HAS_WORKFLOW, "workflow", "tpl", "template");
    // A template link: the link workflow under ws1, and the claim edge naming the pair.
    child("ws1", RelationshipLabel.HAS_WORKFLOW, "workflow", "lnk", "template");
    RelationshipEdgeEntity claim =
        new RelationshipEdgeEntity(
            "workspace:ws1",
            RelationshipLabel.LINKS_TEMPLATE,
            "workflow:lnk",
            Optional.of(Map.of("templateRef", "tpl")));
    claim.setId("linksTemplate:workspace:ws1:tpl");
    edgeRepository.insert(claim);

    child("ws1", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun", "r1a", "r1a");
    child("ws1", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun", "r1b", "r1b");
    child("ws2", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun", "r2a", "r2a");
    child("ws4", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun", "r4a", "r4a");
    child("sys", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun", "rsys", "rsys");
    edge("workspace:ws1", RelationshipLabel.HAS_WORKFLOWRUN, "workflowrun:ghost");
    node("workflowrun", "rorphan", "rorphan");

    node("task", "gt1", "sleep");
    node("task", "gt2", "echo");
    edge("root:root", RelationshipLabel.HAS_TASK, "task:gt1");
    edge("root:root", RelationshipLabel.HAS_TASK, "task:gt2");
    node("task", "gtorphan", "orphan-task");
    child("ws1", RelationshipLabel.HAS_TASK, "workspacetask", "wt1", "sleep");
    child("ws4", RelationshipLabel.HAS_TASK, "workspacetask", "wt4", "sleep");

    child("ws2", RelationshipLabel.HAS_APPROVER_GROUP, "approvergroup", "ag2", "approvers");
    child("ws3", RelationshipLabel.HAS_INTEGRATION, "integration", "in3", "github");
    edge("workspace:ws1", RelationshipLabel.HAS_INTEGRATION, "integration:in3");
  }

  @AfterEach
  void noRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  // ── Exhaustive equivalence ─────────────────────────────────────────────────

  /**
   * Every anchor a check or a filter can start from, every target type, named targets and none,
   * and every confinement: the nodes found equal the walk's. Run with and without a request memo.
   */
  @Test
  void findNodesFindsWhatTheWalkFindsForEveryAnchorTargetAndConfinement() {
    List<String[]> anchors =
        List.of(
            new String[] {"root", "root"},
            new String[] {"user", "u3"},
            new String[] {"user", "u3@test.local"},
            new String[] {"user", "u1"},
            new String[] {"user", "u0"},
            new String[] {"user", "nobody"},
            new String[] {"workspace", "ws1"},
            new String[] {"workspace", "beta"},
            new String[] {"workspace", "ws4"},
            new String[] {"workflow", "wf1"},
            new String[] {"workflow", "lnk"});
    int compared = 0;
    for (boolean memo : List.of(false, true)) {
      for (String[] anchor : anchors) {
        RelationshipType fromType = RelationshipType.valueOfLabel(anchor[0]);
        for (RelationshipType toType : targetTypes()) {
          for (Optional<List<String>> toList : toLists(toType)) {
            for (Map.Entry<Optional<RelationshipType>, Optional<List<String>>> confinement :
                confinements()) {
              request(memo);
              List<String> found =
                  ids(
                      service.findNodes(
                          fromType,
                          anchor[1],
                          toType,
                          toList,
                          confinement.getKey(),
                          confinement.getValue()));
              List<String> expected =
                  ids(
                      walkOracle(
                          fromType,
                          anchor[1],
                          toType,
                          toList,
                          confinement.getKey(),
                          confinement.getValue()));
              assertThat(found)
                  .as(
                      "%s:%s -> %s %s via %s %s (memo %s)",
                      anchor[0], anchor[1], toType, toList, confinement.getKey(),
                      confinement.getValue(), memo)
                  .containsExactlyInAnyOrderElementsOf(expected);
              compared++;
            }
          }
        }
      }
    }
    assertThat(compared).isGreaterThan(5000);
  }

  /**
   * {@code check} and {@code filter} answer what they answered over the walk, for every token
   * class: session user, personal token, admin session, workspace token, workflow token, global
   * token and the security-off identity.
   */
  @Test
  void checkAndFilterAnswerAsTheWalkDidForEveryTokenClass() {
    int compared = 0;
    for (Token identity : identities()) {
      when(identityService.getCurrentIdentity()).thenReturn(identity);
      for (RelationshipType toType : targetTypes()) {
        for (Optional<List<String>> toList : toLists(toType)) {
          for (Map.Entry<Optional<RelationshipType>, Optional<List<String>>> confinement :
              confinements()) {
            request(true);
            String what =
                identity.getType() + ":" + identity.getPrincipal() + " " + toType + " " + toList
                    + " via " + confinement.getValue();
            if (toList.isPresent()) {
              assertThat(
                      service.check(
                          toType, toList.get(), confinement.getKey(), confinement.getValue()))
                  .as("check " + what)
                  .isEqualTo(
                      checkOracle(
                          identity, toType, toList.get(), confinement.getKey(),
                          confinement.getValue()));
            }
            request(true);
            assertThat(
                    service.filter(
                        toType, toList, confinement.getKey(), confinement.getValue(), false))
                .as("filter " + what)
                .containsExactlyInAnyOrderElementsOf(
                    filterOracle(
                        identity, toType, toList, confinement.getKey(), confinement.getValue()));
            compared++;
          }
        }
      }
    }
    assertThat(compared).isGreaterThan(2000);
  }

  /**
   * {@code refsInWorkspaces} places what it placed when it re-read every edge into the nodes the
   * walk found, for every token class, target type and list of workspaces: a node shared by two
   * workspaces, a workspace named by ref and by name at once, one the caller is not in, and none.
   */
  @Test
  void refsInWorkspacesPlacesWhatTheEdgesIntoTheFoundNodesPlacedForEveryTokenClass() {
    List<List<String>> workspaceLists = workspaceLists();
    int compared = 0;
    for (boolean memo : List.of(false, true)) {
      for (Token identity : identities()) {
        when(identityService.getCurrentIdentity()).thenReturn(identity);
        for (RelationshipType type : targetTypes()) {
          for (List<String> workspaces : workspaceLists) {
            request(memo);
            Map<String, List<String>> found = sorted(service.refsInWorkspaces(type, workspaces));
            request(memo);
            assertThat(found)
                .as(
                    "%s:%s %s in %s (memo %s)",
                    identity.getType(), identity.getPrincipal(), type, workspaces, memo)
                .isEqualTo(sorted(refsInWorkspacesOracle(identity, type, workspaces)));
            compared++;
          }
        }
      }
    }
    assertThat(compared).isGreaterThan(1500);
  }

  /**
   * A request that reads the caller's memberships once - as authentication does, before any lookup
   * - answers every check, filter and placement as the walk does, and as a caller with no request,
   * who reads them each time, is answered: for every token class, target type, named targets and
   * none, and every confinement, all inside one request per token class.
   */
  @Test
  void aRequestSharingOneReadOfItsMembershipsAnswersAsOneReadingThemEachTime() {
    int compared = 0;
    for (boolean memo : List.of(true, false)) {
      for (Token identity : identities()) {
        when(identityService.getCurrentIdentity()).thenReturn(identity);
        // One request for everything this identity asks, its memberships read first.
        request(memo);
        assertThat(service.roles(identity.getPrincipal()))
            .as("roles of %s (memo %s)", identity.getPrincipal(), memo)
            .isEqualTo(rolesOracle(identity.getPrincipal()));
        for (RelationshipType toType : targetTypes()) {
          for (Optional<List<String>> toList : toLists(toType)) {
            for (Map.Entry<Optional<RelationshipType>, Optional<List<String>>> confinement :
                confinements()) {
              String what =
                  identity.getType() + ":" + identity.getPrincipal() + " " + toType + " " + toList
                      + " via " + confinement.getValue() + " (memo " + memo + ")";
              if (toList.isPresent()) {
                assertThat(
                        service.check(
                            toType, toList.get(), confinement.getKey(), confinement.getValue()))
                    .as("check " + what)
                    .isEqualTo(
                        checkOracle(
                            identity, toType, toList.get(), confinement.getKey(),
                            confinement.getValue()));
              }
              assertThat(
                      service.filter(
                          toType, toList, confinement.getKey(), confinement.getValue(), false))
                  .as("filter " + what)
                  .containsExactlyInAnyOrderElementsOf(
                      filterOracle(
                          identity, toType, toList, confinement.getKey(), confinement.getValue()));
              compared++;
            }
          }
          for (List<String> workspaces : workspaceLists()) {
            assertThat(sorted(service.refsInWorkspaces(toType, workspaces)))
                .as("%s %s in %s (memo %s)", identity.getPrincipal(), toType, workspaces, memo)
                .isEqualTo(sorted(refsInWorkspacesOracle(identity, toType, workspaces)));
            compared++;
          }
        }
        assertThat(service.roles(identity.getPrincipal()))
            .isEqualTo(rolesOracle(identity.getPrincipal()));
      }
    }
    assertThat(compared).isGreaterThan(8000);
  }

  /**
   * The profile's workflow count per workspace is the number {@code refsInWorkspaces} lists there,
   * for every token class - a plain user, an admin, a global token, a workspace token, a workflow
   * token and the security-off identity - and every list of workspaces, with and without a request.
   */
  @Test
  void theWorkflowCountPerWorkspaceIsWhatTheListingListsForEveryTokenClass() {
    int compared = 0;
    int counted = 0;
    for (boolean memo : List.of(false, true)) {
      for (Token identity : identities()) {
        when(identityService.getCurrentIdentity()).thenReturn(identity);
        for (List<String> workspaces : workspaceLists()) {
          request(memo);
          Map<String, Long> counts = service.workflowCountsInWorkspaces(workspaces);
          request(memo);
          Map<String, Long> listed = new HashMap<>();
          service
              .refsInWorkspaces(RelationshipType.WORKFLOW, workspaces)
              .forEach((workspace, refs) -> listed.put(workspace, (long) refs.size()));
          Map<String, Long> expected = new HashMap<>();
          refsInWorkspacesOracle(identity, RelationshipType.WORKFLOW, workspaces)
              .forEach((workspace, refs) -> expected.put(workspace, (long) refs.size()));
          assertThat(counts)
              .as(
                  "%s:%s counts in %s (memo %s)",
                  identity.getType(), identity.getPrincipal(), workspaces, memo)
              .isEqualTo(listed)
              .isEqualTo(expected);
          compared++;
          counted += counts.size();
        }
      }
    }
    assertThat(compared).isGreaterThan(150);
    assertThat(counted).isGreaterThan(100);
  }

  /**
   * The Workflows page takes its workflows from the lookup by name when the request names any, and
   * from the workspace's nodes when it names none. Each is what the walk finds, for every token
   * class, workspace and naming - a workflow's own token naming itself among them. For that token
   * only the lookup by name finds its workflow, so narrowing the unnamed nodes is not a substitute.
   */
  @Test
  void aWorkspacesWorkflowsNamedOrNotAreTheWalksForEveryTokenClass() {
    Optional<RelationshipType> inWorkspace = Optional.of(RelationshipType.WORKSPACE);
    int compared = 0;
    for (boolean memo : List.of(false, true)) {
      for (Token identity : identities()) {
        when(identityService.getCurrentIdentity()).thenReturn(identity);
        List<Optional<List<String>>> namings = new ArrayList<>(toLists(RelationshipType.WORKFLOW));
        namings.add(Optional.of(List.of(identity.getPrincipal())));
        namings.add(Optional.of(List.of(identity.getPrincipal(), "wf1", "build")));
        for (String workspace : List.of("alpha", "ws2", "gamma", "delta", "system", "nope")) {
          for (Optional<List<String>> names : namings) {
            request(memo);
            List<String> page =
                names.isPresent()
                    ? service.refsInWorkspace(RelationshipType.WORKFLOW, workspace, names)
                    : service.nodesInWorkspace(RelationshipType.WORKFLOW, workspace).stream()
                        .map(RelationshipNodeEntity::getRef)
                        .toList();
            assertThat(page)
                .as(
                    "%s:%s workflows %s in %s (memo %s)",
                    identity.getType(), identity.getPrincipal(), names, workspace, memo)
                .containsExactlyInAnyOrderElementsOf(
                    filterOracle(
                        identity, RelationshipType.WORKFLOW, names, inWorkspace,
                        Optional.of(List.of(workspace))));
            compared++;
          }
        }
      }
    }
    assertThat(compared).isGreaterThan(1500);

    Token ownToken = token(AuthScope.key, "wf1");
    ownToken.setActorKind(TokenActorKind.WORKFLOW);
    when(identityService.getCurrentIdentity()).thenReturn(ownToken);
    assertThat(service.refsInWorkspace(RelationshipType.WORKFLOW, "alpha", Optional.of(List.of("wf1"))))
        .containsExactly("wf1");
    assertThat(service.nodesInWorkspace(RelationshipType.WORKFLOW, "alpha")).isEmpty();
  }

  /** The nodes of a workspace a caller sees are the ones its refs name, with their names. */
  @Test
  void nodesInWorkspaceAreTheNodesRefsInWorkspaceNames() {
    for (Token identity : identities()) {
      when(identityService.getCurrentIdentity()).thenReturn(identity);
      for (RelationshipType type : targetTypes()) {
        for (String workspace : List.of("alpha", "ws2", "gamma", "delta", "system", "nope")) {
          request(true);
          List<RelationshipNodeEntity> nodes = service.nodesInWorkspace(type, workspace);
          assertThat(nodes.stream().map(RelationshipNodeEntity::getRef))
              .as("%s %s in %s", identity.getPrincipal(), type, workspace)
              .containsExactlyInAnyOrderElementsOf(
                  filterOracle(
                      identity,
                      type,
                      Optional.empty(),
                      Optional.of(RelationshipType.WORKSPACE),
                      Optional.of(List.of(workspace))));
          for (RelationshipNodeEntity node : nodes) {
            RelationshipNodeEntity stored = nodeRepository.findById(node.getId()).orElseThrow();
            assertThat(List.of(node.getType(), node.getRef(), node.getSlug()))
                .containsExactly(stored.getType(), stored.getRef(), stored.getSlug());
          }
        }
      }
    }
    assertThat(
            service.childIdsByLabel(
                RelationshipType.WORKSPACE, "alpha", RelationshipLabel.HAS_WORKFLOW))
        .containsExactlyInAnyOrder("workflow:wf1", "workflow:lnk");
    assertThat(
            service.childIdsByLabel(
                RelationshipType.WORKSPACE, "nope", RelationshipLabel.HAS_WORKFLOW))
        .isEmpty();
  }

  /**
   * The labels a walk level follows are computed from the edge shapes: for every pair of types,
   * exactly the labels of the shapes that leave the one and from whose far end the other is
   * reached, worked out here by following the shapes one at a time. None for a type no shape
   * reaches.
   */
  @Test
  @SuppressWarnings("unchecked")
  void theLabelsAWalkFollowsAreTheShapesThatLeadToItsTarget() throws Exception {
    Field shapesField = RelationshipService.class.getDeclaredField("EDGE_SHAPES");
    shapesField.setAccessible(true);
    List<String[]> shapes =
        ((Set<String>) shapesField.get(null)).stream().map(shape -> shape.split("\\|")).toList();
    Method labelsToward =
        RelationshipService.class.getDeclaredMethod("labelsToward", Collection.class, String.class);
    labelsToward.setAccessible(true);

    for (RelationshipType from : RelationshipType.values()) {
      for (RelationshipType to : RelationshipType.values()) {
        Set<String> expected = new TreeSet<>();
        for (String[] shape : shapes) {
          if (shape[0].equals(from.getLabel())
              && reachesByShapes(shapes, shape[2], to.getLabel(), new HashSet<>())) {
            expected.add(shape[1]);
          }
        }
        assertThat(
                new TreeSet<>(
                    (Set<String>)
                        labelsToward.invoke(null, List.of(from.getLabel()), to.getLabel())))
            .as("%s toward %s", from, to)
            .isEqualTo(expected);
      }
    }
    assertThat((Set<String>) labelsToward.invoke(null, List.of("user"), "approvergroup"))
        .containsExactlyInAnyOrder("memberOf");
    assertThat((Set<String>) labelsToward.invoke(null, List.of("workspace"), "approvergroup"))
        .containsExactlyInAnyOrder("hasApproverGroup");
    assertThat((Set<String>) labelsToward.invoke(null, List.of("workspace"), "workflow"))
        .containsExactlyInAnyOrder("hasWorkflow", "linksTemplate");
    assertThat((Set<String>) labelsToward.invoke(null, List.of("root", "user"), "schedule"))
        .isEmpty();
    assertThat((Set<String>) labelsToward.invoke(null, List.of("workflow"), "workflowrun"))
        .isEmpty();
  }

  // Whether a node of the type is, or leads by the shapes to, a node of the target type.
  private static boolean reachesByShapes(
      List<String[]> shapes, String type, String target, Set<String> visited) {
    if (type.equals(target)) {
      return true;
    }
    if (!visited.add(type)) {
      return false;
    }
    for (String[] shape : shapes) {
      if (shape[0].equals(type) && reachesByShapes(shapes, shape[2], target, visited)) {
        return true;
      }
    }
    return false;
  }

  // ── Named cases ────────────────────────────────────────────────────────────

  // The lookups' shortcuts hold for the graph's shape only, so an edge of another shape is refused
  // where it would be written: a user with an edge straight to a run would be reached by a walk
  // the run check no longer makes.
  @Test
  void anEdgeOfAShapeTheLookupsAreNotBuiltForIsRefused() {
    assertThatThrownBy(
            () ->
                service.createEdge(
                    RelationshipType.USER,
                    "u3",
                    RelationshipLabel.MEMBER_OF,
                    RelationshipType.WORKFLOWRUN,
                    "r4a",
                    Optional.empty()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(edgeRepository.findByFromAndLabel("user:u3", "memberOf"))
        .noneMatch(edge -> edge.getTo().startsWith("workflowrun:"));
  }

  @Test
  void aUserInThreeWorkspacesReachesEachOnlyThroughItsOwnWorkspace() {
    asSession("u3");
    assertThat(workflowsIn("alpha")).containsExactlyInAnyOrder("wf1", "lnk");
    assertThat(workflowsIn("beta")).containsExactly("wf2");
    assertThat(workflowsIn("gamma")).containsExactly("wf3");
    assertThat(workflowsIn("delta")).isEmpty();
    assertThat(runIn("r2a", "beta")).isTrue();
    assertThat(runIn("r4a", "delta")).isFalse();
    assertThat(
            service.check(
                RelationshipType.WORKFLOWRUN, "r4a", Optional.empty(), Optional.empty()))
        .isFalse();
  }

  @Test
  void aRemovedMemberIsDeniedOnTheNextRequest() {
    asSession("u3");
    request(true);
    assertThat(runIn("r2a", "beta")).isTrue();
    assertThat(workflowsIn("beta")).containsExactly("wf2");

    service.removeEdge(RelationshipType.USER, "u3", RelationshipType.WORKSPACE, "ws2");

    // The membership is read live: the same request already sees it gone, and so does the next.
    assertThat(runIn("r2a", "beta")).isFalse();
    request(true);
    assertThat(runIn("r2a", "beta")).isFalse();
    assertThat(workflowsIn("beta")).isEmpty();
    assertThat(
            service.check(
                RelationshipType.WORKFLOWRUN, "r2a", Optional.empty(), Optional.empty()))
        .isFalse();
    assertThat(runIn("r1a", "alpha")).isTrue();
  }

  /**
   * A member removed by someone else is denied on the next request. The request that had already
   * read its memberships keeps the one snapshot it took, as it keeps its node resolutions.
   */
  @Test
  void aMemberRemovedBetweenTwoRequestsIsDeniedOnTheSecond() {
    asSession("u3");
    request(true);
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws2", "ws3");
    assertThat(runIn("r2a", "beta")).isTrue();
    assertThat(workflowsIn("beta")).containsExactly("wf2");

    // Another request, on another instance, removes the membership: straight from the store.
    edgeRepository.deleteByFromAndTo("user:u3", "workspace:ws2");

    assertThat(workflowsIn("beta")).as("the request in flight keeps its snapshot").containsExactly("wf2");
    request(true);
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws3");
    assertThat(runIn("r2a", "beta")).isFalse();
    assertThat(workflowsIn("beta")).isEmpty();
    assertThat(service.check(RelationshipType.WORKSPACE, "ws2", Optional.empty(), Optional.empty()))
        .isFalse();
    assertThat(service.workflowCountsInWorkspaces(List.of("alpha", "beta", "gamma")))
        .containsOnlyKeys("alpha", "gamma");
    assertThat(workflowsIn("alpha")).containsExactlyInAnyOrder("wf1", "lnk");

    // With no request there is nothing to keep: every lookup reads the memberships again.
    request(false);
    assertThat(workflowsIn("gamma")).containsExactly("wf3");
    edgeRepository.deleteByFromAndTo("user:u3", "workspace:ws3");
    assertThat(workflowsIn("gamma")).isEmpty();
  }

  /**
   * A request keeps its own caller's memberships only: those read before the request has an
   * identity, as authentication reads them, are the ones the caller's lookups then use, while a
   * read with no identity, or of another user's memberships, goes to the store every time.
   */
  @Test
  void aRequestKeepsOnlyItsOwnCallersMemberships() {
    request(true);
    when(identityService.getCurrentIdentity()).thenReturn(null);
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws2", "ws3");
    edgeRepository.deleteByFromAndTo("user:u3", "workspace:ws3");
    // With no identity yet, each read is the store's.
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws2");
    edgeRepository.deleteByFromAndTo("user:u3", "workspace:ws2");

    // The identity authentication establishes takes the last read made for it.
    asSession("u3");
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws2");
    assertThat(workflowsIn("beta")).containsExactly("wf2");

    // Another caller on the same request reads u3's memberships, and its own, from the store.
    asSession("u1");
    assertThat(service.roles("u3")).containsOnlyKeys("ws1");
    assertThat(service.roles("u1")).containsOnlyKeys("ws1");
    edgeRepository.deleteByFromAndTo("user:u3", "workspace:ws1");
    assertThat(service.roles("u3")).isEmpty();
    // A workspace token's principal names no user: nothing of a user's is kept for it.
    asWorkspaceToken("u3");
    member("u3", "ws4");
    assertThat(service.roles("u3")).containsOnlyKeys("ws4");
  }

  /**
   * A request that changes a membership itself sees the change at once, whichever write made it:
   * a removal, a new member, a role change, a membership edge created or updated directly, and a
   * workspace removed.
   */
  @Test
  void aRequestThatChangesAMembershipSeesItsOwnChange() {
    asSession("u3");
    request(true);
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws2", "ws3");
    assertThat(workflowsIn("delta")).isEmpty();

    service.setMemberRole("u3", "ws4", "owner");
    assertThat(service.roles("u3")).containsEntry("ws4", "owner");
    assertThat(workflowsIn("delta")).containsExactly("wf4");
    assertThat(runIn("r4a", "delta")).isTrue();

    service.setMemberRole("u3", "ws4", "reader");
    assertThat(service.roles("u3")).containsEntry("ws4", "reader");

    service.updateEdgeData(
        RelationshipType.USER, "u3", RelationshipType.WORKSPACE, "ws4", Map.of("role", "editor"));
    assertThat(service.roles("u3")).containsEntry("ws4", "editor");

    service.removeEdge(RelationshipType.USER, "u3", RelationshipType.WORKSPACE, "ws4");
    assertThat(service.roles("u3")).containsOnlyKeys("ws1", "ws2", "ws3");
    assertThat(workflowsIn("delta")).isEmpty();
    assertThat(runIn("r4a", "delta")).isFalse();

    service.createEdge(
        RelationshipType.USER, "u3", RelationshipLabel.MEMBER_OF, RelationshipType.WORKSPACE, "sys",
        Optional.of(Map.of("role", "owner")));
    assertThat(service.roles("u3")).containsEntry("sys", "owner");
    assertThat(workflowsIn("system")).containsExactly("tpl");

    // The current principal's own overloads: leaving, and joining again.
    service.removeEdge(RelationshipType.WORKSPACE, "ws1");
    assertThat(service.roles("u3")).doesNotContainKey("ws1");
    assertThat(workflowsIn("alpha")).isEmpty();
    service.createEdge(RelationshipType.WORKSPACE, "ws1", Optional.of(Map.of("role", "reader")));
    assertThat(workflowsIn("alpha")).containsExactlyInAnyOrder("wf1", "lnk");
    service.updateEdgeData(RelationshipType.WORKSPACE, "ws1", Map.of("role", "owner"));
    assertThat(service.roles("u3")).containsEntry("ws1", "owner");

    service.removeNodeAndEdgeByRef(RelationshipType.WORKSPACE, "ws2");
    assertThat(service.roles("u3")).doesNotContainKey("ws2");
    assertThat(workflowsIn("beta")).isEmpty();

    // A workspace created with its creator as its first member.
    service.createNodeAndEdge(
        RelationshipType.USER, "u3", RelationshipLabel.MEMBER_OF, RelationshipType.WORKSPACE, "ws9",
        "iota", Optional.empty(), Optional.of(Map.of("role", "owner")));
    assertThat(service.roles("u3")).containsEntry("ws9", "owner");
  }

  @Test
  void aRunOutsideThePathWorkspaceIsDenied() {
    asSession("u3");
    assertThat(runIn("r1a", "alpha")).isTrue();
    assertThat(runIn("r1a", "beta")).isFalse();

    asWorkspaceToken("ws1");
    assertThat(runIn("r1a", "alpha")).isTrue();
    assertThat(runIn("r1a", "beta")).isFalse();
    assertThat(runIn("r2a", "beta")).isFalse();
  }

  /** A workflow's token reads its own workflow, and no run: its node has no edge to one. */
  @Test
  void aWorkflowTokenReachesItsOwnWorkflowAndNoRun() {
    Token token = token(AuthScope.key, "wf1");
    token.setActorKind(TokenActorKind.WORKFLOW);
    when(identityService.getCurrentIdentity()).thenReturn(token);
    assertThat(
            service.filter(
                RelationshipType.WORKFLOW, Optional.of(List.of("wf1")), Optional.empty(),
                Optional.empty(), false))
        .containsExactly("wf1");
    assertThat(service.check(RelationshipType.WORKFLOW, "wf1", Optional.empty(), Optional.empty()))
        .isTrue();
    assertThat(runIn("r1a", "alpha")).isFalse();
    assertThat(
            service.check(
                RelationshipType.WORKFLOWRUN, "r1a", Optional.empty(), Optional.empty()))
        .isFalse();
  }

  /** A link and its claim edge are two edges to one node: it is found once, and only in ws1. */
  @Test
  void aTemplateLinkIsFoundOnceInItsOwnWorkspace() {
    asSession("u1");
    assertThat(workflowsIn("alpha")).containsExactlyInAnyOrder("wf1", "lnk");
    assertThat(workflowsIn("system")).isEmpty();
    assertThat(service.check(RelationshipType.WORKFLOW, "lnk", Optional.empty(), Optional.empty()))
        .isTrue();
    assertThat(service.check(RelationshipType.WORKFLOW, "tpl", Optional.empty(), Optional.empty()))
        .isFalse();
  }

  /** The catalogue is the root's tasks: a workspace task of the same name is never one of them. */
  @Test
  void theGlobalTaskCatalogueIsTheRootsTasksOnly() {
    asSession("u0");
    assertThat(
            service.filter(
                RelationshipType.TASK, Optional.empty(), Optional.empty(), Optional.empty(), false))
        .containsExactlyInAnyOrder("gt1", "gt2");
    assertThat(
            service.filter(
                RelationshipType.TASK, Optional.of(List.of("sleep")), Optional.empty(),
                Optional.empty(), false))
        .containsExactly("gt1");
    assertThat(
            service.filter(
                RelationshipType.TASK, Optional.of(List.of("orphan-task")), Optional.empty(),
                Optional.empty(), false))
        .isEmpty();
  }

  // ── Oracle: the walk over every outgoing edge, as it stood ─────────────────

  private boolean checkOracle(
      Token identity,
      RelationshipType type,
      List<String> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    switch (identity.getType()) {
      case session:
      case user:
        if (IdentityService.hasGlobalGrant(identity)) {
          return true;
        }
        return !walkOracle(
                RelationshipType.USER, identity.getPrincipal(), type, Optional.of(toList),
                intermediateType, intermediateList)
            .isEmpty();
      case key:
        return !walkOracle(
                TokenActorKind.WORKFLOW.equals(identity.getActorKind())
                    ? RelationshipType.WORKFLOW
                    : RelationshipType.WORKSPACE,
                identity.getPrincipal(), type, Optional.of(toList), intermediateType,
                intermediateList)
            .isEmpty();
      case global:
        return true;
      default:
        return false;
    }
  }

  private List<String> filterOracle(
      Token identity,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    RelationshipType fromType = null;
    String from = identity.getPrincipal();
    if (RelationshipType.TASK.equals(toType)) {
      fromType = RelationshipType.ROOT;
      from = "root";
    } else {
      switch (identity.getType()) {
        case session:
        case user:
          if (IdentityService.hasGlobalGrant(identity)) {
            fromType = RelationshipType.ROOT;
            from = "root";
          } else {
            fromType = RelationshipType.USER;
          }
          break;
        case key:
          if (TokenActorKind.WORKFLOW.equals(identity.getActorKind())) {
            fromType = RelationshipType.WORKFLOW;
            if (fromType.equals(toType) && toList.isPresent() && toList.get().contains(from)) {
              toList = Optional.of(List.of(from));
              fromType = RelationshipType.ROOT;
              from = "root";
            }
          } else {
            fromType = RelationshipType.WORKSPACE;
          }
          break;
        case global:
          fromType = RelationshipType.ROOT;
          from = "root";
          break;
        default:
          break;
      }
    }
    return (fromType == null)
        ? List.of()
        : walkOracle(fromType, from, toType, toList, intermediateType, intermediateList).stream()
            .map(RelationshipNodeEntity::getRef)
            .toList();
  }

  // refsInWorkspaces as it stood: the filter's refs, then every edge into them, kept where it starts
  // at a named workspace and placed under that workspace as named.
  private Map<String, List<String>> refsInWorkspacesOracle(
      Token identity, RelationshipType type, List<String> workspaces) {
    Map<String, List<String>> refs = new HashMap<>();
    List<String> visible =
        filterOracle(
            identity,
            type,
            Optional.empty(),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.copyOf(workspaces)));
    if (visible.isEmpty()) {
      return refs;
    }
    Map<String, RelationshipNodeEntity> named = new HashMap<>();
    for (RelationshipNodeEntity node :
        nodeRepository.findByTypeAndRefOrSlugIn(
            RelationshipType.WORKSPACE.getLabel(), new HashSet<>(workspaces))) {
      for (String name : List.of(node.getSlug(), node.getRef())) {
        if (workspaces.contains(name)) {
          named.putIfAbsent(name, node);
        }
      }
    }
    Map<String, String> workspaceByNodeId = new HashMap<>();
    named.forEach((workspace, node) -> workspaceByNodeId.put(node.getId(), workspace));
    String prefix = type.getLabel() + ":";
    for (RelationshipEdgeEntity edge :
        edgeRepository.findByToIn(visible.stream().map(ref -> prefix + ref).toList())) {
      if (workspaceByNodeId.containsKey(edge.getFrom())) {
        List<String> placed =
            refs.computeIfAbsent(workspaceByNodeId.get(edge.getFrom()), w -> new ArrayList<>());
        String ref = edge.getTo().substring(prefix.length());
        if (!placed.contains(ref)) {
          placed.add(ref);
        }
      }
    }
    return refs;
  }

  // The refs per workspace in a fixed order, duplicates kept so that a repeat still fails.
  private static Map<String, List<String>> sorted(Map<String, List<String>> refs) {
    Map<String, List<String>> sorted = new HashMap<>();
    refs.forEach((workspace, list) -> sorted.put(workspace, list.stream().sorted().toList()));
    return sorted;
  }

  private List<RelationshipNodeEntity> walkOracle(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    boolean hasIntermediate = intermediateType.isPresent() && intermediateList.isPresent();
    List<RelationshipNodeEntity> starts;
    if (RelationshipType.ROOT.equals(fromType)
        && hasIntermediate
        && RelationshipType.WORKSPACE.equals(intermediateType.get())) {
      Map<String, RelationshipNodeEntity> named = new HashMap<>();
      for (RelationshipNodeEntity node :
          nodeRepository.findByTypeAndRefOrSlugIn(
              RelationshipType.WORKSPACE.getLabel(), new HashSet<>(intermediateList.get()))) {
        for (String name : List.of(node.getSlug(), node.getRef())) {
          if (intermediateList.get().contains(name)) {
            named.putIfAbsent(name, node);
          }
        }
      }
      starts = List.copyOf(named.values());
    } else {
      starts =
          nodeRepository
              .findOneByTypeAndRefOrSlug(fromType.getLabel(), from)
              .map(List::of)
              .orElse(List.of());
    }
    String toTypeLabel = toType.getLabel();
    List<RelationshipNodeEntity> results = new ArrayList<>();
    Map<String, RelationshipNodeEntity> levelNodes = new LinkedHashMap<>();
    Map<String, Boolean> levelPassed = new HashMap<>();
    for (RelationshipNodeEntity start : starts) {
      levelNodes.put(start.getId(), start);
      levelPassed.put(
          start.getId(), passes(start, hasIntermediate, intermediateType, intermediateList));
    }
    Set<String> seen = new HashSet<>(levelNodes.keySet());
    while (!levelNodes.isEmpty()) {
      List<String> expandIds = new ArrayList<>();
      for (Map.Entry<String, RelationshipNodeEntity> entry : levelNodes.entrySet()) {
        RelationshipNodeEntity node = entry.getValue();
        if (node.getType().equals(toTypeLabel)) {
          boolean interOk = !hasIntermediate || levelPassed.getOrDefault(entry.getKey(), false);
          if (interOk
              && (toList.isEmpty()
                  || toList.get().contains(node.getRef())
                  || toList.get().contains(node.getSlug()))) {
            results.add(node);
          }
        } else {
          expandIds.add(entry.getKey());
        }
      }
      if (expandIds.isEmpty()) {
        break;
      }
      List<RelationshipEdgeEntity> edges = edgeRepository.findByFromIn(expandIds);
      Map<String, Boolean> childPassed = new HashMap<>();
      for (RelationshipEdgeEntity edge : edges) {
        if (!seen.contains(edge.getTo())) {
          childPassed.merge(
              edge.getTo(), levelPassed.getOrDefault(edge.getFrom(), false), Boolean::logicalOr);
        }
      }
      Map<String, RelationshipNodeEntity> nextNodes = new LinkedHashMap<>();
      Map<String, Boolean> nextPassed = new HashMap<>();
      for (RelationshipNodeEntity child : nodeRepository.findAllById(childPassed.keySet())) {
        nextNodes.put(child.getId(), child);
        nextPassed.put(
            child.getId(),
            childPassed.getOrDefault(child.getId(), false)
                || passes(child, hasIntermediate, intermediateType, intermediateList));
        seen.add(child.getId());
      }
      levelNodes = nextNodes;
      levelPassed = nextPassed;
    }
    return results;
  }

  private static boolean passes(
      RelationshipNodeEntity node,
      boolean hasIntermediate,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    return hasIntermediate
        && node.getType().equals(intermediateType.get().getLabel())
        && (intermediateList.get().contains(node.getRef())
            || intermediateList.get().contains(node.getSlug()));
  }

  // ── Fixture helpers ────────────────────────────────────────────────────────

  private static List<RelationshipType> targetTypes() {
    return List.of(
        RelationshipType.WORKSPACE,
        RelationshipType.WORKFLOW,
        RelationshipType.WORKFLOWRUN,
        RelationshipType.TASK,
        RelationshipType.WORKSPACETASK,
        RelationshipType.APPROVERGROUP,
        RelationshipType.INTEGRATION,
        RelationshipType.USER,
        // No edge shape runs to a schedule or to the root: a walk for one follows no label.
        RelationshipType.SCHEDULE,
        RelationshipType.ROOT);
  }

  // No list, an empty one, an unknown name, and per type single and mixed refs and names.
  private static List<Optional<List<String>>> toLists(RelationshipType type) {
    List<List<String>> named =
        switch (type) {
          case WORKSPACE -> List.of(List.of("ws1"), List.of("beta"), List.of("ws4", "gamma"));
          case WORKFLOW ->
              List.of(
                  List.of("wf1"), List.of("build"), List.of("alpha"), List.of("lnk"),
                  List.of("template"), List.of("wf4", "wf3"));
          case WORKFLOWRUN ->
              List.of(
                  List.of("r1a"), List.of("r2a"), List.of("r4a"), List.of("rsys"),
                  List.of("ghost"), List.of("rorphan"), List.of("r4a", "r1b"),
                  List.of("r1a", "r1b", "r2a"));
          case TASK -> List.of(List.of("gt1"), List.of("sleep"), List.of("orphan-task", "echo"));
          case WORKSPACETASK -> List.of(List.of("wt1"), List.of("sleep"));
          case APPROVERGROUP -> List.of(List.of("ag2"), List.of("approvers"));
          case INTEGRATION -> List.of(List.of("in3"), List.of("github"));
          case USER -> List.of(List.of("u3"), List.of("u1@test.local"));
          default -> List.of();
        };
    List<Optional<List<String>>> lists = new ArrayList<>();
    lists.add(Optional.empty());
    lists.add(Optional.of(List.of()));
    lists.add(Optional.of(List.of("nope")));
    named.forEach(list -> lists.add(Optional.of(list)));
    return lists;
  }

  private static List<Map.Entry<Optional<RelationshipType>, Optional<List<String>>>>
      confinements() {
    Optional<RelationshipType> workspace = Optional.of(RelationshipType.WORKSPACE);
    return List.of(
        Map.entry(Optional.empty(), Optional.empty()),
        Map.entry(workspace, Optional.of(List.of("alpha"))),
        Map.entry(workspace, Optional.of(List.of("ws2"))),
        Map.entry(workspace, Optional.of(List.of("gamma"))),
        Map.entry(workspace, Optional.of(List.of("alpha", "delta"))),
        Map.entry(workspace, Optional.of(List.of("system"))),
        Map.entry(workspace, Optional.of(List.of())),
        Map.entry(workspace, Optional.of(List.of("nope"))),
        Map.entry(workspace, Optional.empty()),
        Map.entry(Optional.of(RelationshipType.USER), Optional.of(List.of("u3"))),
        Map.entry(Optional.of(RelationshipType.WORKFLOWRUN), Optional.of(List.of("r1a"))),
        Map.entry(Optional.of(RelationshipType.WORKFLOW), Optional.of(List.of("wf1"))));
  }

  private static List<List<String>> workspaceLists() {
    return List.of(
        List.of("alpha", "beta", "gamma"),
        List.of("ws1", "ws3"),
        List.of("ws1", "ws4"),
        List.of("alpha", "ws1"),
        List.of("system"),
        List.of("nope", "beta"),
        List.of("ws1", "ws2", "ws3", "ws4", "sys"),
        List.of());
  }

  // A user's workspaces and roles, straight from the stored memberOf edges.
  private Map<String, String> rolesOracle(String principal) {
    Map<String, String> roles = new HashMap<>();
    for (RelationshipEdgeEntity edge : edgeRepository.findByFromAndLabel("user:" + principal, "memberOf")) {
      roles.putIfAbsent(edge.getTo().split(":")[1], edge.getData().getOrDefault("role", "reader"));
    }
    return roles;
  }

  private List<Token> identities() {
    List<Token> identities = new ArrayList<>();
    for (String user : List.of("u3", "u1", "u0", "nobody")) {
      identities.add(token(AuthScope.session, user));
    }
    identities.add(token(AuthScope.user, "u3"));
    Token admin = new Token(AuthScope.session);
    admin.setPrincipal("u0");
    admin.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    identities.add(admin);
    identities.add(token(AuthScope.key, "ws1"));
    identities.add(token(AuthScope.key, "ws4"));
    for (String workflow : List.of("wf1", "lnk")) {
      Token workflowToken = token(AuthScope.key, workflow);
      workflowToken.setActorKind(TokenActorKind.WORKFLOW);
      identities.add(workflowToken);
    }
    Token global = new Token(AuthScope.global);
    global.setPrincipal("service");
    global.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    identities.add(global);
    identities.add(new UnauthenticatedGlobalToken());
    return identities;
  }

  // A full-access grant scoped to one workspace: the relationship walk alone decides.
  private static Token token(AuthScope type, String principal) {
    Token token = new Token(type);
    token.setPrincipal(principal);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.workspace, "ws1", List.of("**/**"))));
    return token;
  }

  private void asSession(String user) {
    when(identityService.getCurrentIdentity()).thenReturn(token(AuthScope.session, user));
  }

  private void asWorkspaceToken(String workspace) {
    when(identityService.getCurrentIdentity()).thenReturn(token(AuthScope.key, workspace));
  }

  private List<String> workflowsIn(String workspace) {
    return service.refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.empty());
  }

  private boolean runIn(String run, String workspace) {
    return service.check(
        RelationshipType.WORKFLOWRUN,
        run,
        Optional.of(RelationshipType.WORKSPACE),
        Optional.of(List.of(workspace)));
  }

  // A fresh request scope, so the node memo starts empty, or none at all.
  private static void request(boolean memo) {
    if (memo) {
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest()));
    } else {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static List<String> ids(List<RelationshipNodeEntity> nodes) {
    return nodes.stream().map(RelationshipNodeEntity::getId).collect(Collectors.toList());
  }

  private void node(String type, String ref, String slug) {
    nodeRepository.save(new RelationshipNodeEntity(type, ref, slug, Optional.empty()));
  }

  private void edge(String from, RelationshipLabel label, String to) {
    edgeRepository.save(new RelationshipEdgeEntity(from, label, to, Optional.of(Map.of())));
  }

  private void member(String user, String workspace) {
    edgeRepository.save(
        new RelationshipEdgeEntity(
            "user:" + user,
            RelationshipLabel.MEMBER_OF,
            "workspace:" + workspace,
            Optional.of(Map.of("role", "editor"))));
  }

  private void child(
      String workspace, RelationshipLabel label, String type, String ref, String slug) {
    node(type, ref, slug);
    edge("workspace:" + workspace, label, type + ":" + ref);
  }
}
