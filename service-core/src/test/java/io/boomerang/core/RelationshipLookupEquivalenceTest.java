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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
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

  private IdentityService identityService;
  private RelationshipService service;

  @BeforeEach
  void aGraphOfTheProductionShape() {
    nodeRepository.deleteAll();
    edgeRepository.deleteAll();
    identityService = mock(IdentityService.class);
    service =
        new RelationshipService(
            nodeRepository, edgeRepository, identityService, new SimpleMeterRegistry());

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
    List<List<String>> workspaceLists =
        List.of(
            List.of("alpha", "beta", "gamma"),
            List.of("ws1", "ws3"),
            List.of("ws1", "ws4"),
            List.of("alpha", "ws1"),
            List.of("system"),
            List.of("nope", "beta"),
            List.of("ws1", "ws2", "ws3", "ws4", "sys"),
            List.of());
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
        RelationshipType.USER);
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
