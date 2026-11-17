package io.boomerang.core;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
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
 *       edges that exist - access is enforced by the hierarchy itself; a check on named runs reads
 *       the runs' own incoming edges instead, so no lookup reads a workspace's runs unless it asks
 *       for them ({@link #findNodes})
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
 * <p>The graph is real Mongo-backed behaviour in every deployment: {@code filter}/{@code check}/
 * {@code findNodes} carry ref-resolution and access-control semantics, so there is no no-op
 * variant. A single-workspace instance ({@code flow.workspace.single=true}) simply holds one
 * workspace node.
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

  /**
   * {@link #resolveNode} for several refs-or-slugs of one type, keyed by the value asked for; one
   * that resolves to nothing is absent. The ones not already memoised are read in one query.
   */
  private Map<String, RelationshipNodeEntity> resolveNodes(
      RelationshipType type, Collection<String> refsOrSlugs) {
    Map<String, RelationshipNodeEntity> nodes = new HashMap<>();
    Map<String, RelationshipNodeEntity> memo = requestMemo();
    Set<String> missing = new HashSet<>();
    for (String refOrSlug : new HashSet<>(refsOrSlugs)) {
      String key = type.getLabel() + ":" + refOrSlug;
      if (memo != null && memo.containsKey(key)) {
        nodes.put(refOrSlug, memo.get(key));
      } else {
        missing.add(refOrSlug);
      }
    }
    if (missing.isEmpty()) {
      return nodes;
    }
    for (RelationshipNodeEntity node :
        nodeRepository.findByTypeAndRefOrSlugIn(type.getLabel(), missing)) {
      for (String refOrSlug : List.of(node.getSlug(), node.getRef())) {
        if (missing.contains(refOrSlug)) {
          nodes.putIfAbsent(refOrSlug, node);
          if (memo != null) {
            memo.putIfAbsent(type.getLabel() + ":" + refOrSlug, node);
          }
        }
      }
    }
    return nodes;
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
    requireEdgeShape(fromResult.getType(), label, toResult.getType());
    edgeRepository.save(
        new RelationshipEdgeEntity(fromResult.getId(), label, toResult.getId(), data));
  }

  /*
   * Creates the Relationship Edge for the current principal
   */
  public void createEdge(RelationshipType toType, String to, Optional<Map<String, String>> data) {
    Token identity = requireCurrentIdentity();
    LOGGER.debug("Creating edge for: {}", identity.getPrincipal());
    this.createEdge(
        identityRelationshipType(identity),
        identity.getPrincipal(),
        RelationshipLabel.MEMBER_OF,
        toType,
        to,
        data);
  }

  /**
   * the {@link RelationshipType} an identity's OWN node lives under - shared by every
   * "for the current principal" convenience overload ({@link #createEdge(RelationshipType,
   * String, Optional)}, {@link #updateEdgeData(RelationshipType, String, Map)}, {@link
   * #removeEdge(RelationshipType, String)}). {@code identity.getType().getLabel()}
   * (session/user/key/global) no longer maps 1:1 onto a {@code RelationshipType} the way the
   * retired {@code workspace}/{@code workflow} token classes used to - {@code key} folds them
   * together, so {@code actorKind} (only ever {@code WORKFLOW} here) tells them apart, same as
   * {@link #check}/{@link #filter} above.
   */
  private RelationshipType identityRelationshipType(Token identity) {
    switch (identity.getType()) {
      case session:
      case user:
        return RelationshipType.USER;
      case key:
        return TokenActorKind.WORKFLOW.equals(identity.getActorKind())
            ? RelationshipType.WORKFLOW
            : RelationshipType.WORKSPACE;
      default:
        return RelationshipType.valueOfLabel(identity.getType().getLabel());
    }
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
    requireEdgeShape(fromResult.getType(), label, toType.getLabel());
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
    Token identity = requireCurrentIdentity();
    this.updateEdgeData(
        identityRelationshipType(identity), identity.getPrincipal(), toType, to, data);
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
    Token identity = requireCurrentIdentity();
    this.removeEdge(identityRelationshipType(identity), identity.getPrincipal(), toType, to);
  }

  /**
   * Resolves the current principal for the "for the current principal" convenience overloads
   * ({@link #createEdge(RelationshipType, String, Optional)}, {@link #updateEdgeData(RelationshipType,
   * String, Map)}, {@link #removeEdge(RelationshipType, String)}).
   *
   * <p>Unlike {@link #check}/{@link #filter} - where "no principal on the SecurityContext" (e.g.
   * {@code flow.security.enabled=false}) is a data-scoping question with a meaningful unscoped
   * answer ("allow everything, there is nothing to narrow by") - these overloads mutate an edge
   * FOR an identity. There is no "me" to create/update/remove a relationship for when no principal
   * resolved, so this fails clearly instead of NPE-ing on {@code identity.getPrincipal()}.
   */
  private Token requireCurrentIdentity() {
    Token identity = identityService.getCurrentIdentity();
    if (identity == null) {
      throw new BoomerangException(BoomerangError.AUTH_REQUIRED);
    }
    return identity;
  }

  /**
   * Claim {@code key} under a {@code from} node with an edge to the node {@code toType:toRef},
   * which need not exist yet. The edge's id names the from node, the label and the key, so MongoDB
   * admits exactly one: true when this caller's insert made the claim, false when it is held. The
   * edge runs between nodes the graph already joins (a workspace and its workflow), so it widens no
   * walk, and removing either node removes the claim.
   */
  public boolean claimEdge(
      RelationshipType fromType,
      String from,
      RelationshipLabel label,
      String key,
      RelationshipType toType,
      String toRef,
      Map<String, String> data) {
    RelationshipNodeEntity fromNode = resolveNodeOrThrow(fromType, from);
    requireEdgeShape(fromNode.getType(), label, toType.getLabel());
    RelationshipEdgeEntity edge =
        new RelationshipEdgeEntity(
            fromNode.getId(), label, toType.getLabel() + ":" + toRef, Optional.of(data));
    edge.setId(claimId(fromNode.getId(), label, key));
    try {
      edgeRepository.insert(edge);
      return true;
    } catch (DuplicateKeyException held) {
      return false;
    }
  }

  /** The claim {@link #claimEdge} made for {@code key} under the from node, if one is held. */
  public Optional<RelationshipEdgeEntity> findClaim(
      RelationshipType fromType, String from, RelationshipLabel label, String key) {
    return resolveNode(fromType, from)
        .flatMap(node -> edgeRepository.findById(claimId(node.getId(), label, key)));
  }

  /** Give up a claim, only while it still runs to {@code toType:toRef}; true when removed. */
  public boolean releaseClaim(
      RelationshipType fromType,
      String from,
      RelationshipLabel label,
      String key,
      RelationshipType toType,
      String toRef) {
    return resolveNode(fromType, from)
        .map(
            node ->
                edgeRepository.deleteByIdAndTo(
                        claimId(node.getId(), label, key), toType.getLabel() + ":" + toRef)
                    > 0)
        .orElse(false);
  }

  private static String claimId(String fromNodeId, RelationshipLabel label, String key) {
    return label.getLabel() + ":" + fromNodeId + ":" + key;
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
      // Enforced (ruled 2026-08-31): a failed permission check IS a denial. The shadow
      // would-deny path was retired with the v4-compat requirement.
      LOGGER.warn(
          "RelationshipService - denied principal: {}, token type: {}, resource: {}:{}",
          principal,
          identity.getType(),
          type.getLabel(),
          toList);
      meterRegistry
          .counter(
              "flow.security.denied",
              "resource",
              type.getLabel(),
              "action",
              "check",
              "type",
              identity.getType().toString(),
              "layer",
              "relationship")
          .increment();
      return false;
    }
    switch (identity.getType()) {
      case session:
      case user:
        // An admin's or operator's session carries global grants: platform-wide authority, so it
        // reaches every node like a global token; any other user walks from their own node.
        if (IdentityService.hasGlobalGrant(identity)) {
          return true;
        }
        return hasNodes(
            RelationshipType.USER,
            principal,
            type,
            Optional.of(toList),
            intermediateType,
            intermediateList);
      case key:
        // `key` folds the retired `workflow`/`workspace` classes together - actorKind
        // (only ever WORKFLOW here) tells them apart. No actorKind (or a non-workflow one, e.g.
        // SERVICE) means the old `workspace` shape: principal is a workspace id.
        return hasNodes(
            TokenActorKind.WORKFLOW.equals(identity.getActorKind())
                ? RelationshipType.WORKFLOW
                : RelationshipType.WORKSPACE,
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
    // check() carries no per-request action - action granularity is AuthCriteriaAuthorizationManager's job at
    // the endpoint. The relationship-layer question is only "does any grant cover this resource
    // type at all", so an editor's **/read or **/write passes here exactly like an owner's **/**.
    // The pre-enforcement literal-string comparison ("**/**" or "<type>/**" only) silently
    // denied every editor/reader-shaped grant once check() became load-bearing.
    String resourceCoveredRegex = "(\\*{2}|" + type.getLabel() + ")\\/.+";
    boolean resourceCovered =
        permissions.stream()
            .flatMap(permission -> permission.getActions().stream())
            .anyMatch(action -> action.matches(resourceCoveredRegex));
    if (resourceCovered) {
      return true;
    }
    // Check all specific resources are valid
    List<String> flattenedPermissionPrincipals =
        permissions.stream()
            .map(ResolvedPermissions::getPrincipal)
            .collect(Collectors.toList());
    return flattenedPermissionPrincipals.containsAll(toList);
  }

  /**
   * Return whether the current identity holds a grant for {@code resource}/{@code action} that
   * counts in {@code workspace}, named by id or name. A global grant, or one made to every
   * principal ({@code **}), counts in every workspace. A workspace grant counts only in its own
   * workspace; a workflow's key token holds its grant in its workflow's workspace. A user's
   * self-service grants belong to no workspace, so they never count here.
   */
  public boolean grantsIn(String workspace, PermissionResource resource, PermissionAction action) {
    return grantsIn(identityService.getCurrentIdentity(), workspace, resource, action);
  }

  /** {@link #grantsIn(String, PermissionResource, PermissionAction)} for an identity in hand. */
  public boolean grantsIn(
      Token identity, String workspace, PermissionResource resource, PermissionAction action) {
    if (identity == null || identity.getPermissions() == null) {
      return false;
    }
    String required = IdentityService.grantPattern(resource, action);
    List<ResolvedPermissions> matching =
        identity.getPermissions().stream()
            .filter(
                grant ->
                    grant.getActions() != null
                        && grant.getActions().stream().anyMatch(a -> a.matches(required)))
            .toList();
    if (matching.stream()
        .anyMatch(
            grant ->
                PermissionScope.global.equals(grant.getScope())
                    || "**".equals(grant.getPrincipal()))) {
      return true;
    }
    Set<String> principals =
        matching.stream()
            .filter(grant -> PermissionScope.workspace.equals(grant.getScope()))
            .map(ResolvedPermissions::getPrincipal)
            .collect(Collectors.toSet());
    if (principals.isEmpty() || workspace == null || workspace.isBlank()) {
      return false;
    }
    RelationshipNodeEntity node = resolveNode(RelationshipType.WORKSPACE, workspace).orElse(null);
    if (node == null) {
      return false;
    }
    if (principals.contains(node.getRef()) || principals.contains(node.getSlug())) {
      return true;
    }
    return AuthScope.key.equals(identity.getType())
        && TokenActorKind.WORKFLOW.equals(identity.getActorKind())
        && principals.contains(identity.getPrincipal())
        && node.getRef()
            .equals(
                getParentByLabel(
                    RelationshipLabel.HAS_WORKFLOW,
                    RelationshipType.WORKFLOW,
                    identity.getPrincipal()));
  }

  /**
   * The refs of the {@code type} nodes in {@code workspace} that the current principal may see,
   * narrowed to {@code refsOrSlugs} when given - the lookup most workspace-scoped reads and writes
   * start from.
   */
  public List<String> refsInWorkspace(
      RelationshipType type, String workspace, Optional<List<String>> refsOrSlugs) {
    return filter(
        type,
        refsOrSlugs,
        Optional.of(RelationshipType.WORKSPACE),
        Optional.of(List.of(workspace)),
        false);
  }

  /**
   * {@link #refsInWorkspace} for several workspaces, keyed by the workspace as named: one walk from
   * the current principal through any of them, which places each node found under the named
   * workspaces whose edges into it the walk followed. A named workspace the walk never reached still
   * counts its edges into a node found, read in one more query only when there is one. A workspace
   * with none is absent.
   */
  public Map<String, List<String>> refsInWorkspaces(
      RelationshipType type, Collection<String> workspaces) {
    Map<String, List<String>> refs = new HashMap<>();
    Anchor anchor = filterAnchor(identityService.getCurrentIdentity(), type, Optional.empty());
    if (anchor == null) {
      return refs;
    }
    Map<String, String> workspaceByNodeId = new HashMap<>();
    resolveNodes(RelationshipType.WORKSPACE, workspaces)
        .forEach((workspace, node) -> workspaceByNodeId.put(node.getId(), workspace));
    Placement placement = new Placement(workspaceByNodeId);
    List<RelationshipNodeEntity> found =
        findNodes(
            anchor.type(),
            anchor.from(),
            type,
            anchor.toList(),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.copyOf(workspaces)),
            placement);
    if (found.isEmpty()) {
      return refs;
    }
    Set<String> unwalked = new HashSet<>(workspaceByNodeId.keySet());
    unwalked.removeAll(placement.expanded);
    if (!unwalked.isEmpty()) {
      edgeRepository
          .findByFromInAndToIn(unwalked, found.stream().map(RelationshipNodeEntity::getId).toList())
          .forEach(edge -> placement.place(edge.getFrom(), edge.getTo()));
    }
    for (RelationshipNodeEntity node : found) {
      // A node reached by two edges from one workspace is still one node.
      for (String workspace : placement.workspacesByChild.getOrDefault(node.getId(), Set.of())) {
        refs.computeIfAbsent(workspace, w -> new ArrayList<>()).add(node.getRef());
      }
    }
    return refs;
  }

  /**
   * The named workspaces a walk passes through, and the nodes their edges run to: where {@link
   * #refsInWorkspaces} places what the walk finds, taken from the edges the walk reads anyway.
   */
  private static final class Placement {
    private final Map<String, String> workspaceByNodeId;
    private final Map<String, Set<String>> workspacesByChild = new HashMap<>();
    private final Set<String> expanded = new HashSet<>();

    private Placement(Map<String, String> workspaceByNodeId) {
      this.workspaceByNodeId = workspaceByNodeId;
    }

    private void place(String from, String to) {
      String workspace = workspaceByNodeId.get(from);
      if (workspace != null) {
        workspacesByChild.merge(to, Set.of(workspace), Placement::union);
      }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
      Set<String> both = new HashSet<>(a);
      both.addAll(b);
      return both;
    }
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
    Anchor anchor = filterAnchor(identityService.getCurrentIdentity(), toType, toRefsOrSlugs);
    if (anchor != null) {
      LOGGER.debug(
          "Filtering {} for {}:{}", toType.getLabel(), anchor.type().getLabel(), anchor.from());
      refs =
          findNodes(
                  anchor.type(),
                  anchor.from(),
                  toType,
                  anchor.toList(),
                  intermediateType,
                  intermediateList)
              .stream()
              .map(returnSlugs ? RelationshipNodeEntity::getSlug : RelationshipNodeEntity::getRef)
              .collect(Collectors.toList());
    }
    LOGGER.debug("Filtered {}: {}", toType.getLabel(), refs);
    return refs;
  }

  /** The node a filter walks from, and the refs or slugs it looks for there. */
  private record Anchor(RelationshipType type, String from, Optional<List<String>> toList) {}

  // Where a filter for the identity starts its walk; null when the identity's class reaches nothing.
  private static Anchor filterAnchor(
      Token identity, RelationshipType toType, Optional<List<String>> toRefsOrSlugs) {
    String from = identity.getPrincipal();
    if (RelationshipType.TASK.equals(toType)) {
      // Tasks are a global catalogue: every principal sees every task, so the walk anchors
      // at the root node instead of the principal.
      return new Anchor(RelationshipType.ROOT, "root", toRefsOrSlugs);
    }
    switch (identity.getType()) {
      case session:
      case user:
        // An admin's or operator's session carries global grants and sees every node of the
        // type, as a global token does; any other user sees what their own node reaches.
        return IdentityService.hasGlobalGrant(identity)
            ? new Anchor(RelationshipType.ROOT, "root", toRefsOrSlugs)
            : new Anchor(RelationshipType.USER, from, toRefsOrSlugs);
      case key:
        // `key` folds the retired `workflow`/`workspace` classes together - actorKind
        // (only ever WORKFLOW here) tells them apart, same as check() above.
        if (!TokenActorKind.WORKFLOW.equals(identity.getActorKind())) {
          return new Anchor(RelationshipType.WORKSPACE, from, toRefsOrSlugs);
        }
        if (RelationshipType.WORKFLOW.equals(toType)
            && toRefsOrSlugs.isPresent()
            && toRefsOrSlugs.get().contains(from)) {
          // A workflow token accessing its own workflow record: anchor at root, scoped to
          // itself, as the workflow node has no outgoing edge back to itself.
          return new Anchor(RelationshipType.ROOT, "root", Optional.of(List.of(from)));
        }
        return new Anchor(RelationshipType.WORKFLOW, from, toRefsOrSlugs);
      case global:
        // Allow anything with no filtering - retrieve all nodes of a type in the system.
        return new Anchor(RelationshipType.ROOT, "root", toRefsOrSlugs);
      default:
        return null;
    }
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

  /** Return the refs of every parent reached by an incoming edge with the label. */
  public List<String> getParentsByLabel(RelationshipLabel label, RelationshipType type, String ref) {
    return edgeRepository.findByToAndLabel(type.getLabel() + ":" + ref, label.getLabel()).stream()
        .map(edge -> edge.getFrom().split(":")[1])
        .toList();
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
  public Map<String, String> membersAndRoles(String workspace) {
    RelationshipNodeEntity teamNode = resolveNodeOrThrow(RelationshipType.WORKSPACE, workspace);
    return membersAndRoles(
        edgeRepository.findByToAndLabel(teamNode.getId(), RelationshipLabel.MEMBER_OF.getLabel()));
  }

  /**
   * {@link #membersAndRoles(String)} for several workspaces, keyed by the workspace as named: one
   * edge query for all of them. A workspace with no node is absent.
   */
  public Map<String, Map<String, String>> membersAndRoles(Collection<String> workspaces) {
    Map<String, RelationshipNodeEntity> nodes =
        resolveNodes(RelationshipType.WORKSPACE, workspaces);
    if (nodes.isEmpty()) {
      return Map.of();
    }
    Map<String, List<RelationshipEdgeEntity>> edgesByNode =
        edgeRepository
            .findByToInAndLabel(
                nodes.values().stream().map(RelationshipNodeEntity::getId).toList(),
                RelationshipLabel.MEMBER_OF.getLabel())
            .stream()
            .collect(Collectors.groupingBy(RelationshipEdgeEntity::getTo));
    Map<String, Map<String, String>> members = new HashMap<>();
    nodes.forEach(
        (workspace, node) ->
            members.put(
                workspace, membersAndRoles(edgesByNode.getOrDefault(node.getId(), List.of()))));
    return members;
  }

  // The user MEMBER_OF edges into a workspace as member id -> role.
  private static Map<String, String> membersAndRoles(List<RelationshipEdgeEntity> edges) {
    return edges.stream()
        .filter(e -> e.getFrom().startsWith(RelationshipType.USER.getLabel() + ":"))
        .collect(
            Collectors.toMap(
                e -> e.getFrom().split(":")[1],
                RelationshipService::roleOrDefault,
                (a, b) -> a));
  }

  private static String roleOrDefault(RelationshipEdgeEntity edge) {
    String role = edge.getData() != null ? edge.getData().get("role") : null;
    return (role == null || role.isEmpty()) ? "reader" : role;
  }

  /**
   * Makes the user a member of the workspace with the role. A user already a member keeps their
   * one membership and takes the new role, on every copy of the edge an earlier write duplicated;
   * a second edge would never be read, since a user's roles take the first per workspace.
   */
  /*
   * The edges the graph is made of, as from type, label and to type. The lookups take shortcuts
   * that hold only for this shape (runsReached, namedChildWorkspaces, globalTasks, and the walk
   * skipping run edges): a user reaches only workspaces, only a workspace reaches a run, nothing
   * starts at a run or a workflow, and the root reaches tasks through hasTask. A new kind of edge
   * is refused here until those lookups are checked against it; add it with
   * RelationshipLookupEquivalenceTest covering it.
   */
  private static final Set<String> EDGE_SHAPES =
      Set.of(
          "root|contains|user",
          "root|contains|workspace",
          "root|hasTask|task",
          "user|memberOf|workspace",
          "workspace|hasWorkflow|workflow",
          "workspace|hasWorkflowRun|workflowrun",
          "workspace|hasTask|workspacetask",
          "workspace|hasApproverGroup|approvergroup",
          "workspace|hasIntegration|integration",
          "workspace|linksTemplate|workflow");

  private static void requireEdgeShape(String fromType, RelationshipLabel label, String toType) {
    if (!EDGE_SHAPES.contains(fromType + "|" + label.getLabel() + "|" + toType)) {
      throw new IllegalStateException(
          "Relationship edge " + fromType + " -" + label.getLabel() + "-> " + toType
              + " is not a shape the lookups are built for");
    }
  }

  public void setMemberRole(String userId, String workspace, String role) {
    RelationshipNodeEntity userNode = resolveNodeOrThrow(RelationshipType.USER, userId);
    RelationshipNodeEntity workspaceNode = resolveNodeOrThrow(RelationshipType.WORKSPACE, workspace);
    Map<String, String> data = Map.of("role", role);
    boolean member =
        edgeRepository
            .findByFromAndLabel(userNode.getId(), RelationshipLabel.MEMBER_OF.getLabel())
            .stream()
            .anyMatch(edge -> workspaceNode.getId().equals(edge.getTo()));
    if (member) {
      edgeRepository.updateDataByFromAndTo(userNode.getId(), workspaceNode.getId(), data);
    } else {
      edgeRepository.save(
          new RelationshipEdgeEntity(
              userNode.getId(), RelationshipLabel.MEMBER_OF, workspaceNode.getId(), Optional.of(data)));
    }
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

  // ── Batch node lookups, independent of the calling identity ────────────────

  /** Return the nodes of any of the types with these refs, keyed by ref, in one query. */
  public Map<String, RelationshipNodeEntity> nodesByRef(
      Collection<String> refs, RelationshipType... types) {
    Map<String, RelationshipNodeEntity> nodes = new HashMap<>();
    nodeRepository
        .findAllById(
            Arrays.stream(types)
                .flatMap(type -> refs.stream().map(ref -> type.getLabel() + ":" + ref))
                .toList())
        .forEach(node -> nodes.put(node.getRef(), node));
    return nodes;
  }

  /** Return the nodes of one type carrying any of these slugs, keyed by slug, in one query. */
  public Map<String, RelationshipNodeEntity> nodesBySlug(
      RelationshipType type, Collection<String> slugs) {
    Map<String, RelationshipNodeEntity> nodes = new HashMap<>();
    nodeRepository
        .findByTypeAndSlugIn(type.getLabel(), slugs)
        .forEach(node -> nodes.put(node.getSlug(), node));
    return nodes;
  }

  /**
   * Return, for each node, the parent node reached by an incoming edge with the label, keyed by
   * the child's id: one edge query and one node query however many children are asked for.
   */
  public Map<String, RelationshipNodeEntity> parentsByLabel(
      RelationshipLabel label, Collection<String> childIds) {
    if (childIds.isEmpty()) {
      return Map.of();
    }
    Map<String, String> parentIdByChild = new HashMap<>();
    edgeRepository
        .findByToInAndLabel(childIds, label.getLabel())
        .forEach(edge -> parentIdByChild.putIfAbsent(edge.getTo(), edge.getFrom()));
    Map<String, RelationshipNodeEntity> parentById = new HashMap<>();
    nodeRepository
        .findAllById(parentIdByChild.values())
        .forEach(node -> parentById.put(node.getId(), node));
    Map<String, RelationshipNodeEntity> parents = new HashMap<>();
    parentIdByChild.forEach(
        (child, parent) -> {
          if (parentById.containsKey(parent)) {
            parents.put(child, parentById.get(parent));
          }
        });
    return parents;
  }

  /** Return the child nodes of a node reached by outgoing edges with the label. */
  public List<RelationshipNodeEntity> childrenByLabel(
      RelationshipType fromType, String from, RelationshipLabel label) {
    return resolveNode(fromType, from)
        .map(
            node ->
                nodeRepository.findAllById(
                    edgeRepository.findByFromAndLabel(node.getId(), label.getLabel()).stream()
                        .map(RelationshipEdgeEntity::getTo)
                        .toList()))
        .orElse(List.of());
  }

  /**
   * {@link #childrenByLabel(RelationshipType, String, RelationshipLabel)} for several nodes of one
   * type, keyed by the node as named: one edge query and one node query for all of them. A node
   * with no children, or none at all, is absent.
   */
  public Map<String, List<RelationshipNodeEntity>> childrenByLabel(
      RelationshipType fromType, Collection<String> from, RelationshipLabel label) {
    Map<String, String> nameByNodeId = new HashMap<>();
    resolveNodes(fromType, from).forEach((name, node) -> nameByNodeId.put(node.getId(), name));
    Map<String, List<RelationshipNodeEntity>> children = new HashMap<>();
    if (nameByNodeId.isEmpty()) {
      return children;
    }
    List<RelationshipEdgeEntity> edges =
        edgeRepository.findByFromInAndLabel(nameByNodeId.keySet(), label.getLabel());
    if (edges.isEmpty()) {
      return children;
    }
    Map<String, RelationshipNodeEntity> childById = new HashMap<>();
    nodeRepository
        .findAllById(edges.stream().map(RelationshipEdgeEntity::getTo).toList())
        .forEach(node -> childById.put(node.getId(), node));
    for (RelationshipEdgeEntity edge : edges) {
      if (childById.containsKey(edge.getTo())) {
        children
            .computeIfAbsent(nameByNodeId.get(edge.getFrom()), name -> new ArrayList<>())
            .add(childById.get(edge.getTo()));
      }
    }
    return children;
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
   *
   * <p>The graph is {@code root -> user|workspace|task}, {@code user -> workspace} and {@code
   * workspace -> workflow|workflowrun|workspacetask|approvergroup|integration}, and nothing hangs
   * below a run or a workflow, so these lookups read only the edges that can lead to the target and
   * find exactly the nodes the full walk finds:
   *
   * <ul>
   *   <li>A walk confined to named workspaces starts at those workspaces: from the root, every one
   *       that exists; from a user, those of the user's own workspaces it names.
   *   <li>Named runs are found from their own incoming edges: a run counts when its workspace is
   *       the anchor, or one the anchoring user reaches by an edge read now.
   *   <li>The global task catalogue is the root's {@code hasTask} children.
   *   <li>A walk for anything but a run leaves the {@code hasWorkflowRun} edges unread.
   * </ul>
   *
   * <p>A walk reads each node's type, ref and slug only, so the nodes it returns carry no data; a
   * walk for named nodes reads only those of the type it names.
   */
  public List<RelationshipNodeEntity> findNodes(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    return findNodes(fromType, from, toType, toList, intermediateType, intermediateList, null);
  }

  // findNodes, recording into the placement, when there is one, the edges the walk follows.
  private List<RelationshipNodeEntity> findNodes(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList,
      Placement placement) {
    // A present-but-empty intermediate list is an intermediate filter that matches nothing.
    boolean hasIntermediate = intermediateType.isPresent() && intermediateList.isPresent();
    boolean inWorkspaces =
        hasIntermediate && RelationshipType.WORKSPACE.equals(intermediateType.get());

    if (RelationshipType.ROOT.equals(fromType) && inWorkspaces) {
      return walk(
          List.copyOf(resolveNodes(RelationshipType.WORKSPACE, intermediateList.get()).values()),
          toType,
          toList,
          hasIntermediate,
          intermediateType,
          intermediateList,
          placement);
    }
    RelationshipNodeEntity anchor = resolveNode(fromType, from).orElse(null);
    if (anchor == null) {
      LOGGER.debug("findNodes() - anchor {}:{} not found", fromType.getLabel(), from);
      return new ArrayList<>();
    }
    if (RelationshipType.ROOT.equals(fromType)
        && RelationshipType.TASK.equals(toType)
        && !hasIntermediate) {
      return globalTasks(anchor, toList);
    }
    if (RelationshipType.WORKFLOWRUN.equals(toType)
        && toList.isPresent()
        && (!hasIntermediate || inWorkspaces)
        && (RelationshipType.USER.equals(fromType)
            || RelationshipType.WORKSPACE.equals(fromType))) {
      return runsReached(anchor, toList.get(), hasIntermediate, intermediateList);
    }
    List<RelationshipNodeEntity> starts =
        (RelationshipType.USER.equals(fromType) && inWorkspaces)
            ? namedChildWorkspaces(anchor, intermediateList.get(), toType)
            : List.of(anchor);
    return walk(
        starts, toType, toList, hasIntermediate, intermediateType, intermediateList, placement);
  }

  // The workspaces a user's node has an edge to that the names match: where a walk from the user,
  // confined to those workspaces, passes the confinement. The others lead to no node it finds.
  private List<RelationshipNodeEntity> namedChildWorkspaces(
      RelationshipNodeEntity user, List<String> workspaces, RelationshipType toType) {
    List<String> children =
        edgesFrom(List.of(user.getId()), toType).stream()
            .map(RelationshipEdgeEntity::getTo)
            .filter(id -> !id.equals(user.getId()))
            .distinct()
            .toList();
    return children.isEmpty()
        ? List.of()
        : nodeRepository.findByIdInAndTypeAndRefOrSlugIn(
            children, RelationshipType.WORKSPACE.getLabel(), workspaces);
  }

  /*
   * The named runs a walk from the anchor - a workspace, or a user - finds. A run is a leaf hanging
   * from its workspace, so it is found when an edge into it starts at the anchor, or at a node the
   * anchoring user has an edge to; confined to named workspaces, that node must be one of them.
   */
  private List<RelationshipNodeEntity> runsReached(
      RelationshipNodeEntity anchor,
      List<String> runs,
      boolean hasIntermediate,
      Optional<List<String>> workspaces) {
    Map<String, RelationshipNodeEntity> runById = new HashMap<>();
    resolveNodes(RelationshipType.WORKFLOWRUN, runs)
        .values()
        .forEach(run -> runById.put(run.getId(), run));
    if (runById.isEmpty()) {
      return new ArrayList<>();
    }
    List<RelationshipEdgeEntity> into = edgeRepository.findByToIn(runById.keySet());
    Optional<RelationshipType> workspaceType = Optional.of(RelationshipType.WORKSPACE);
    Set<String> through;
    if (RelationshipType.WORKSPACE.getLabel().equals(anchor.getType())) {
      through =
          (!hasIntermediate || matchesIntermediate(anchor, true, workspaceType, workspaces))
              ? Set.of(anchor.getId())
              : Set.of();
    } else {
      Set<String> parents =
          into.stream().map(RelationshipEdgeEntity::getFrom).collect(Collectors.toSet());
      List<String> reached =
          edgeRepository.findByFromAndToIn(anchor.getId(), parents).stream()
              .map(RelationshipEdgeEntity::getTo)
              .toList();
      // A run is a leaf: the walk never goes on from one, so none leads to another.
      through =
          nodeRepository.findAllById(reached).stream()
              .filter(node -> !RelationshipType.WORKFLOWRUN.getLabel().equals(node.getType()))
              .filter(
                  node ->
                      !hasIntermediate
                          || matchesIntermediate(node, true, workspaceType, workspaces))
              .map(RelationshipNodeEntity::getId)
              .collect(Collectors.toSet());
    }
    return into.stream()
        .filter(edge -> through.contains(edge.getFrom()))
        .map(edge -> runById.get(edge.getTo()))
        .distinct()
        .collect(Collectors.toList());
  }

  // The global task catalogue under the root, optionally the tasks of these refs or names: every
  // task node the root has an edge to, and no workspace's own tasks, which are another type.
  private List<RelationshipNodeEntity> globalTasks(
      RelationshipNodeEntity root, Optional<List<String>> toList) {
    String label = RelationshipLabel.HAS_TASK.getLabel();
    if (toList.isEmpty()) {
      return nodeRepository
          .findAllById(
              edgeRepository.findByFromAndLabel(root.getId(), label).stream()
                  .map(RelationshipEdgeEntity::getTo)
                  .toList())
          .stream()
          .filter(node -> RelationshipType.TASK.getLabel().equals(node.getType()))
          .collect(Collectors.toList());
    }
    List<RelationshipNodeEntity> named =
        nodeRepository.findByTypeAndRefOrSlugIn(RelationshipType.TASK.getLabel(), toList.get());
    if (named.isEmpty()) {
      return new ArrayList<>();
    }
    Set<String> underRoot =
        edgeRepository
            .findByToInAndLabel(named.stream().map(RelationshipNodeEntity::getId).toList(), label)
            .stream()
            .filter(edge -> root.getId().equals(edge.getFrom()))
            .map(RelationshipEdgeEntity::getTo)
            .collect(Collectors.toSet());
    return named.stream()
        .filter(node -> underRoot.contains(node.getId()))
        .collect(Collectors.toList());
  }

  // A walk level's outgoing edges, as their ends. Runs are leaves under their workspace, so a walk
  // for anything else leaves their edges unread; the from_label index skips them.
  private List<RelationshipEdgeEntity> edgesFrom(List<String> ids, RelationshipType toType) {
    return RelationshipType.WORKFLOWRUN.equals(toType)
        ? edgeRepository.findEndsByFromIn(ids)
        : edgeRepository.findEndsByFromInAndLabelNot(
            ids, RelationshipLabel.HAS_WORKFLOWRUN.getLabel());
  }

  // A walk level's nodes as type, ref and slug. A node of the target type that none of the names
  // match is neither found nor walked past, so a walk for named nodes leaves it unread.
  private List<RelationshipNodeEntity> readLevel(
      Collection<String> ids, RelationshipType toType, Optional<List<String>> toList) {
    return toList.isEmpty()
        ? nodeRepository.findRefsByIdIn(ids)
        : nodeRepository.findRefsByIdInUnlessTypeAndUnnamed(ids, toType.getLabel(), toList.get());
  }

  // The level-by-level downward walk of findNodes, from one or more start nodes.
  private List<RelationshipNodeEntity> walk(
      List<RelationshipNodeEntity> starts,
      RelationshipType toType,
      Optional<List<String>> toList,
      boolean hasIntermediate,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList,
      Placement placement) {
    String toTypeLabel = toType.getLabel();
    List<RelationshipNodeEntity> results = new ArrayList<>();
    Map<String, RelationshipNodeEntity> levelNodes = new HashMap<>();
    Map<String, Boolean> levelPassed = new HashMap<>();
    for (RelationshipNodeEntity start : starts) {
      levelNodes.put(start.getId(), start);
      levelPassed.put(
          start.getId(),
          matchesIntermediate(start, hasIntermediate, intermediateType, intermediateList));
    }
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
      if (placement != null) {
        placement.expanded.addAll(expandIds);
      }

      List<RelationshipEdgeEntity> edges = edgesFrom(expandIds, toType);
      if (edges.isEmpty()) {
        break;
      }

      Map<String, Boolean> childPassed = new HashMap<>();
      for (RelationshipEdgeEntity edge : edges) {
        String childId = edge.getTo();
        if (placement != null) {
          placement.place(edge.getFrom(), childId);
        }
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
      for (RelationshipNodeEntity child : readLevel(childPassed.keySet(), toType, toList)) {
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
