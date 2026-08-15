package io.boomerang.core;

import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Entity relationships and access control, backed by direct MongoDB queries against the
 * {@code rel_nodes} / {@code rel_edges} collections.
 *
 * <p>The data model is a directed graph of nodes (id = {@code type:ref}) and labelled edges
 * ({@code from}/{@code to} = node ids). Relationships are shallow ({@code root -> team ->
 * workflow|schedule|... }, {@code user -> team}) so every lookup is served by an indexed
 * edge/node query rather than an in-memory graph:
 *
 * <ul>
 *   <li>node existence/resolve -> {@code type_ref_idx} / {@code type_slug_idx}
 *   <li>1-hop parents / membership -> incoming edges ({@code findByTo*}, {@code to_label_idx})
 *   <li>principal-scoped {@code check}/{@code filter} -> a level-by-level downward walk anchored
 *       at the principal's node (one edge query + one node batch-load per level), following only
 *       edges that exist - access is enforced by the hierarchy itself
 * </ul>
 *
 * <p>Reading live Mongo on every call means there is no per-instance cache to go stale across
 * replicas - authz decisions made by any instance see every committed write immediately. Node
 * resolutions are memoised per HTTP request only; off-request callers (scheduler threads) simply
 * skip the memo.
 *
 * <p>Write ordering: callers persist the DOMAIN document first, then create the node/edge - the
 * relationship graph is an index over domain truth, never the source of it.
 *
 * <p><b>E8 mode-gating note (I3/J-C):</b> left ungated (loads in every {@code flow.mode}) rather
 * than swapped for a single-anchor/default no-op in {@code engine}/{@code standalone}. A
 * no-op seam would only be safe for the narrow always-on call set (workflow, event,
 * {@code RelationshipEventListener}, non-team api) - but the api {@code Workspace*} surface
 * ({@code WorkspaceWorkflowService} and friends) also stays constructed in every mode (required
 * unconditionally by {@code ScheduleJob}'s fire path in {@code standalone}, and by the api
 * mode-matrix row - "same surface, team-&gt;default" - which keeps it live in {@code engine}
 * too), and that surface leans on {@code filter}/{@code check}/{@code findNodes} for real
 * ref-resolution and access-control semantics, not decoration. No-opping those would silently
 * corrupt workflow/task/run resolution rather than gracefully degrade it, which is worse than
 * today's behaviour. So this is the documented fallback: real Mongo-backed behaviour in every
 * mode; only {@code workspace} (team/quota CRUD, gated {@link
 * io.boomerang.config.FlowMode#FULL}) actually stops writing to it outside full mode. J1's
 * default-team remapping for the always-on {@code Workspace*} surface remains deferred (E10
 * territory).
 */
@Component
public class RelationshipService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String NODE_MEMO_ATTRIBUTE =
      RelationshipService.class.getName() + ".nodeMemo";

  private final RelationshipNodeRepository nodeRepository;
  private final RelationshipEdgeRepository edgeRepository;
  private final IdentityService identityService;
  private final MeterRegistry meterRegistry;

  public RelationshipService(
      RelationshipNodeRepository nodeRepository,
      RelationshipEdgeRepository edgeRepository,
      IdentityService identityService,
      MeterRegistry meterRegistry) {
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.identityService = identityService;
    this.meterRegistry = meterRegistry;
  }

  // ── Node resolution (direct, index-backed, always type-scoped) ─────────────

  /**
   * Resolve a node by type + ref-or-slug. Always type-scoped: a slug match never crosses types.
   * Positive resolutions are memoised for the duration of the current HTTP request.
   */
  private Optional<RelationshipNodeEntity> resolveNode(RelationshipType type, String refOrSlug) {
    String key = type.getLabel() + ":" + refOrSlug;
    Map<String, RelationshipNodeEntity> memo = requestMemo();
    if (memo != null && memo.containsKey(key)) {
      return Optional.of(memo.get(key));
    }
    Optional<RelationshipNodeEntity> node =
        nodeRepository.findOneByTypeAndRefOrSlug(type.getLabel(), refOrSlug);
    if (memo != null && node.isPresent()) {
      memo.put(key, node.get());
    }
    return node;
  }

  private RelationshipNodeEntity resolveNodeOrThrow(RelationshipType type, String refOrSlug) {
    return resolveNode(type, refOrSlug)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Node does not exist: " + type.getLabel() + ":" + refOrSlug));
  }

  /**
   * Return the per-request node memo, or {@code null} when no HTTP request is active (e.g.
   * scheduler threads) - memoisation is then a no-op, never an error.
   */
  @SuppressWarnings("unchecked")
  private Map<String, RelationshipNodeEntity> requestMemo() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    Map<String, RelationshipNodeEntity> memo =
        (Map<String, RelationshipNodeEntity>)
            attributes.getAttribute(NODE_MEMO_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (memo == null) {
      memo = new HashMap<>();
      attributes.setAttribute(NODE_MEMO_ATTRIBUTE, memo, RequestAttributes.SCOPE_REQUEST);
    }
    return memo;
  }

  private void clearRequestMemo() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.removeAttribute(NODE_MEMO_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }
  }

  // ── Mutations (persist to Mongo; no in-memory graph to rebuild) ────────────

  /*
   * Creates the Relationship Node mapped to an object in the system
   */
  public RelationshipNodeEntity createNode(
      RelationshipType type, String ref, String slug, Optional<Map<String, String>> data) {
    return nodeRepository.save(new RelationshipNodeEntity(type.getLabel(), ref, slug, data));
  }

  /*
   * Creates the Relationship Edge linking two Nodes. Nodes have to exist.
   */
  public void createEdge(
      RelationshipType fromType,
      String from,
      RelationshipLabel label,
      RelationshipType toType,
      String to,
      Optional<Map<String, String>> data) {
    RelationshipNodeEntity fromResult = resolveNodeOrThrow(fromType, from);
    RelationshipNodeEntity toResult = resolveNodeOrThrow(toType, to);
    edgeRepository.save(
        new RelationshipEdgeEntity(fromResult.getId(), label, toResult.getId(), data));
  }

  /*
   * Creates the Relationship Edge for the current principal
   */
  public void createEdge(RelationshipType toType, String to, Optional<Map<String, String>> data) {
    Token identity = identityService.getCurrentIdentity();
    LOGGER.debug("Creating edge for: {}", identity.getPrincipal());
    RelationshipType fromType;
    if (AuthScope.session.equals(identity.getType())) {
      fromType = RelationshipType.USER;
    } else {
      fromType = RelationshipType.valueOfLabel(identity.getType().getLabel());
    }
    this.createEdge(
        fromType, identity.getPrincipal(), RelationshipLabel.MEMBER_OF, toType, to, data);
  }

  /*
   * Creates the Relationship Node mapped to an object in the system and its Edge from an
   * existing parent Node
   */
  public void createNodeAndEdge(
      RelationshipType fromType,
      String from,
      RelationshipLabel label,
      RelationshipType toType,
      String toRef,
      String toSlug,
      Optional<Map<String, String>> nodeData,
      Optional<Map<String, String>> edgeData) {
    RelationshipNodeEntity fromResult = resolveNodeOrThrow(fromType, from);
    RelationshipNodeEntity toNode = this.createNode(toType, toRef, toSlug, nodeData);
    edgeRepository.save(
        new RelationshipEdgeEntity(fromResult.getId(), label, toNode.getId(), edgeData));
  }

  /*
   * Update the Relationship Edge's data
   */
  public void updateEdgeData(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      String to,
      Map<String, String> data)
      throws IllegalArgumentException {
    RelationshipNodeEntity fromNode = resolveNodeOrThrow(fromType, from);
    RelationshipNodeEntity toNode = resolveNodeOrThrow(toType, to);
    edgeRepository.updateDataByFromAndTo(fromNode.getId(), toNode.getId(), data);
  }

  /*
   * Update the Relationship Edge's data for current principal
   */
  public void updateEdgeData(RelationshipType toType, String to, Map<String, String> data) {
    Token identity = identityService.getCurrentIdentity();
    this.updateEdgeData(
        RelationshipType.valueOfLabel(identity.getType().getLabel()),
        identity.getPrincipal(),
        toType,
        to,
        data);
  }

  /*
   * Removes the Relationship Edge
   */
  public void removeEdge(RelationshipType fromType, String from, RelationshipType toType, String to)
      throws IllegalArgumentException {
    RelationshipNodeEntity fromNode = resolveNodeOrThrow(fromType, from);
    RelationshipNodeEntity toNode = resolveNodeOrThrow(toType, to);
    edgeRepository.deleteByFromAndTo(fromNode.getId(), toNode.getId());
  }

  /*
   * Removes the Relationship Edge for current Principal
   */
  public void removeEdge(RelationshipType toType, String to) {
    Token identity = identityService.getCurrentIdentity();
    this.removeEdge(
        RelationshipType.valueOfLabel(identity.getType().getLabel()),
        identity.getPrincipal(),
        toType,
        to);
  }

  /*
   * Removes the Relationship Node and all Edges linked to it
   */
  public void removeNodeAndEdgeByRefOrSlug(RelationshipType type, String refOrSlug) {
    RelationshipNodeEntity node = nodeRepository.deleteByRefOrSlug(type.getLabel(), refOrSlug);
    if (node != null) {
      edgeRepository.deleteByFromOrTo(node.getId());
    }
    clearRequestMemo();
  }

  /*
   * Removes the Relationship Node By Ref and all Edges linked to it
   */
  public void removeNodeAndEdgeByRef(RelationshipType type, String ref) {
    RelationshipNodeEntity node = nodeRepository.deleteByTypeAndRef(type.getLabel(), ref);
    if (node != null) {
      edgeRepository.deleteByFromOrTo(node.getId());
    }
    clearRequestMemo();
  }

  /*
   * Updates the slug of a Node
   */
  public void updateNodeByRefOrSlug(RelationshipType type, String refOrSlug, String newSlug) {
    nodeRepository.updateSlugByTypeAndRefOrSlug(type.getLabel(), refOrSlug, newSlug);
    clearRequestMemo();
  }

  // ── Existence / resolve ────────────────────────────────────────────────────

  /*
   * Checks if the slug or ref exists for the Relationship Type. It does not filter for
   * Relationship - can probably only be used for top level nodes
   */
  public boolean doesSlugOrRefExistForType(RelationshipType type, String refOrSlug) {
    LOGGER.debug("Checking {}:{} for existence", type.getLabel(), refOrSlug);
    return nodeRepository.existsByTypeAndRefOrSlug(type.getLabel(), refOrSlug);
  }

  /*
   * Retrieves the slug for the Node by type and slug or ref
   *
   * This should only be used from unique ID to return slug. Otherwise multiples for the wrong
   * team could be returned.
   */
  public String getSlugByRefForType(RelationshipType type, String refOrSlug) {
    LOGGER.debug("Retrieving slug for {}:{}", type.getLabel(), refOrSlug);
    return resolveNodeOrThrow(type, refOrSlug).getSlug();
  }

  // ── Access control ─────────────────────────────────────────────────────────

  /*
   * Check if the current principal has the relationship & permission to access the object
   */
  public boolean check(
      RelationshipType type,
      String to,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    return check(type, List.of(to), intermediateType, intermediateList);
  }

  /*
   * Check if the current principal has the relationship & permission to access the objects
   */
  public boolean check(
      RelationshipType type,
      List<String> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    Token identity = identityService.getCurrentIdentity();
    String principal = identity.getPrincipal();
    if (!checkPermissions(identity.getPermissions(), type, toList)) {
      // Shadow enforcement: the failed permission check is recorded but not enforced -
      // access remains relationship-based. Flip to `return false` here when permission
      // enforcement becomes authoritative.
      LOGGER.warn(
          "RelationshipService - would deny principal: {}, token type: {}, resource: {}:{}",
          principal,
          identity.getType(),
          type.getLabel(),
          toList);
      meterRegistry
          .counter(
              "flow.security.would.deny",
              "resource",
              type.getLabel(),
              "action",
              "check",
              "type",
              identity.getType().toString(),
              "layer",
              "relationship")
          .increment();
    }
    switch (identity.getType()) {
      case session:
      case user:
        return hasNodes(
            RelationshipType.USER,
            principal,
            type,
            Optional.of(toList),
            intermediateType,
            intermediateList);
      case workflow:
        return hasNodes(
            RelationshipType.WORKFLOW,
            principal,
            type,
            Optional.of(toList),
            intermediateType,
            intermediateList);
      case workspace:
        return hasNodes(
            RelationshipType.WORKSPACE,
            principal,
            type,
            Optional.of(toList),
            intermediateType,
            intermediateList);
      case global:
        // Allow anything with no filtering
        return true;
      default:
        return false;
    }
  }

  private static boolean checkPermissions(
      List<ResolvedPermissions> permissions, RelationshipType type, List<String> toList) {
    List<String> flattenedPermissionActions =
        permissions.stream()
            .flatMap(permission -> permission.getActions().stream())
            .collect(Collectors.toList());
    // Full access or full access for object
    if (flattenedPermissionActions.contains("**/**")
        || flattenedPermissionActions.contains(type.getLabel() + "/**")) {
      return true;
    }
    // Check all specific resources are valid
    List<String> flattenedPermissionPrincipals =
        permissions.stream()
            .map(ResolvedPermissions::getPrincipal)
            .collect(Collectors.toList());
    return flattenedPermissionPrincipals.containsAll(toList);
  }

  /*
   * Filter objects to subset the current principal has the relationship & permission to access
   */
  @Deprecated
  public List<String> filter(RelationshipType toType, Optional<List<String>> toRefsOrSlugs) {
    return filter(toType, toRefsOrSlugs, Optional.empty(), Optional.empty());
  }

  public List<String> filter(
      RelationshipType toType,
      Optional<List<String>> toRefsOrSlugs,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    return filter(toType, toRefsOrSlugs, intermediateType, intermediateList, true);
  }

  /*
   * Filter objects to subset the current principal has the relationship & permission to access
   *
   * Optionally pass in intermediateType and intermediateList to filter by an intermediate node
   */
  public List<String> filter(
      RelationshipType toType,
      Optional<List<String>> toRefsOrSlugs,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList,
      Boolean returnSlugs) {
    List<String> refs = new ArrayList<>();
    Token identity = identityService.getCurrentIdentity();
    RelationshipType fromType = null;
    String from = identity.getPrincipal();

    if (RelationshipType.TASK.equals(toType)) {
      // Tasks are a global catalogue: every principal sees every task, so the walk anchors
      // at the root node instead of the principal.
      fromType = RelationshipType.ROOT;
      from = "root";
    } else {
      switch (identity.getType()) {
        case session:
        case user:
          fromType = RelationshipType.USER;
          break;
        case workflow:
          fromType = RelationshipType.WORKFLOW;
          if (fromType.equals(toType)
              && toRefsOrSlugs.isPresent()
              && toRefsOrSlugs.get().contains(from)) {
            // A workflow token accessing its own workflow record: anchor at root, scoped to
            // itself, as the workflow node has no outgoing edge back to itself.
            toRefsOrSlugs = Optional.of(List.of(from));
            fromType = RelationshipType.ROOT;
            from = "root";
          }
          break;
        case workspace:
          fromType = RelationshipType.WORKSPACE;
          break;
        case global:
          // Allow anything with no filtering - retrieve all nodes of a type in the system.
          fromType = RelationshipType.ROOT;
          from = "root";
          break;
      }
    }

    if (!Objects.isNull(fromType)) {
      LOGGER.debug("Filtering {} for {}:{}", toType.getLabel(), fromType.getLabel(), from);
      refs =
          findNodes(fromType, from, toType, toRefsOrSlugs, intermediateType, intermediateList)
              .stream()
              .map(returnSlugs ? RelationshipNodeEntity::getSlug : RelationshipNodeEntity::getRef)
              .collect(Collectors.toList());
    }
    LOGGER.debug("Filtered {}: {}", toType.getLabel(), refs);
    return refs;
  }

  // ── Membership / roles (direct edge queries) ───────────────────────────────

  /*
   * Retrieve the Parent by Label (incoming edge)
   */
  public String getParentByLabel(RelationshipLabel label, RelationshipType type, String ref) {
    List<RelationshipEdgeEntity> edges =
        edgeRepository.findByToAndLabel(type.getLabel() + ":" + ref, label.getLabel());
    String parent = "";
    if (!edges.isEmpty()) {
      parent = edges.stream().findFirst().get().getFrom().split(":")[1];
    }
    return parent;
  }

  /*
   * Retrieve the Teams and Roles for current Principal
   */
  public Map<String, String> roles(String principal) {
    List<RelationshipEdgeEntity> edges =
        edgeRepository.findByFromAndLabel(
            "user:" + principal, RelationshipLabel.MEMBER_OF.getLabel());
    return edges.stream()
        .collect(
            Collectors.toMap(
                e -> e.getTo().split(":")[1],
                RelationshipService::roleOrDefault,
                (a, b) -> a));
  }

  /*
   * Retrieve the Members and Roles for a Workspace (the incoming user MEMBER_OF edges)
   */
  public Map<String, String> membersAndRoles(String team) {
    RelationshipNodeEntity teamNode = resolveNodeOrThrow(RelationshipType.WORKSPACE, team);
    return edgeRepository
        .findByToAndLabel(teamNode.getId(), RelationshipLabel.MEMBER_OF.getLabel())
        .stream()
        .filter(e -> e.getFrom().startsWith(RelationshipType.USER.getLabel() + ":"))
        .collect(
            Collectors.toMap(
                e -> e.getFrom().split(":")[1],
                RelationshipService::roleOrDefault,
                (a, b) -> a));
  }

  private static String roleOrDefault(RelationshipEdgeEntity edge) {
    String role = edge.getData() != null ? edge.getData().get("role") : null;
    return (role == null || role.isEmpty()) ? "viewer" : role;
  }

  // ── Traversal ──────────────────────────────────────────────────────────────

  /*
   * Retrieve the node refs of type toType reachable from the given node
   */
  public List<String> findNodeRefs(
      RelationshipType fromType, String from, RelationshipType toType) {
    return findNodes(fromType, from, toType, Optional.empty(), Optional.empty(), Optional.empty())
        .stream()
        .map(RelationshipNodeEntity::getRef)
        .collect(Collectors.toList());
  }

  /**
   * Find nodes of {@code toType} reachable downward from the {@code from} node, optionally
   * limited to {@code toList} refs/slugs and to paths passing through an intermediate node in
   * {@code intermediateList}.
   *
   * <p>Implemented as a level-by-level downward walk over outgoing edges (one edge query and one
   * node batch-load per level), anchored at the {@code from} node. Because it follows only edges
   * that exist, access control is enforced by the hierarchy: a walk from a user's node reaches
   * only teams they are a member of, and their descendants.
   */
  public List<RelationshipNodeEntity> findNodes(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    // A present-but-empty intermediate list is an intermediate filter that matches nothing.
    boolean hasIntermediate = intermediateType.isPresent() && intermediateList.isPresent();
    String toTypeLabel = toType.getLabel();

    Optional<RelationshipNodeEntity> fromOpt = resolveNode(fromType, from);
    if (fromOpt.isEmpty()) {
      LOGGER.debug("findNodes() - anchor {}:{} not found", fromType.getLabel(), from);
      return new ArrayList<>();
    }
    RelationshipNodeEntity fromNode = fromOpt.get();

    List<RelationshipNodeEntity> results = new ArrayList<>();
    Map<String, RelationshipNodeEntity> levelNodes = new HashMap<>();
    Map<String, Boolean> levelPassed = new HashMap<>();
    levelNodes.put(fromNode.getId(), fromNode);
    levelPassed.put(
        fromNode.getId(),
        matchesIntermediate(fromNode, hasIntermediate, intermediateType, intermediateList));
    Set<String> seen = new HashSet<>(levelNodes.keySet());

    while (!levelNodes.isEmpty()) {
      List<String> expandIds = new ArrayList<>();
      for (Map.Entry<String, RelationshipNodeEntity> entry : levelNodes.entrySet()) {
        RelationshipNodeEntity node = entry.getValue();
        if (node.getType().equals(toTypeLabel)) {
          boolean interOk = !hasIntermediate || levelPassed.getOrDefault(entry.getKey(), false);
          if (interOk && matchesToList(node, toList)) {
            results.add(node);
          }
          // Target nodes are the leaves we want; do not expand past them.
        } else {
          expandIds.add(entry.getKey());
        }
      }
      if (expandIds.isEmpty()) {
        break;
      }

      List<RelationshipEdgeEntity> edges = edgeRepository.findByFromIn(expandIds);
      if (edges.isEmpty()) {
        break;
      }

      Map<String, Boolean> childPassed = new HashMap<>();
      for (RelationshipEdgeEntity edge : edges) {
        String childId = edge.getTo();
        if (seen.contains(childId)) {
          continue;
        }
        boolean parentPassed = levelPassed.getOrDefault(edge.getFrom(), false);
        childPassed.merge(childId, parentPassed, Boolean::logicalOr);
      }
      if (childPassed.isEmpty()) {
        break;
      }

      Map<String, RelationshipNodeEntity> nextNodes = new HashMap<>();
      Map<String, Boolean> nextPassed = new HashMap<>();
      for (RelationshipNodeEntity child : nodeRepository.findAllById(childPassed.keySet())) {
        boolean passed =
            childPassed.getOrDefault(child.getId(), false)
                || matchesIntermediate(child, hasIntermediate, intermediateType, intermediateList);
        nextNodes.put(child.getId(), child);
        nextPassed.put(child.getId(), passed);
        seen.add(child.getId());
      }
      levelNodes = nextNodes;
      levelPassed = nextPassed;
    }
    LOGGER.debug("Found Node(s)[{}]: {}", results.size(), results);
    return results;
  }

  public boolean hasNodes(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    boolean has =
        !findNodes(fromType, from, toType, toList, intermediateType, intermediateList).isEmpty();
    LOGGER.debug("Has Node(s): {}", has);
    return has;
  }

  private static boolean matchesToList(RelationshipNodeEntity node, Optional<List<String>> toList) {
    return toList.isEmpty()
        || toList.get().contains(node.getRef())
        || toList.get().contains(node.getSlug());
  }

  private static boolean matchesIntermediate(
      RelationshipNodeEntity node,
      boolean hasIntermediate,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    if (!hasIntermediate) {
      return false;
    }
    if (!node.getType().equals(intermediateType.get().getLabel())) {
      return false;
    }
    return intermediateList.get().contains(node.getRef())
        || intermediateList.get().contains(node.getSlug());
  }
}
