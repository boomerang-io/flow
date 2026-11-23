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
import com.mongodb.client.MongoCollection;
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
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
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
 * resolutions and the caller's memberships are memoised per HTTP request only, so a request sees
 * one snapshot of each and the next request reads them again; off-request callers (scheduler
 * threads) simply skip the memo.
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
  private static final String MEMBERSHIP_MEMO_ATTRIBUTE =
      RelationshipService.class.getName() + ".membershipMemo";

  private final RelationshipNodeRepository nodeRepository;
  private final RelationshipEdgeRepository edgeRepository;
  private final IdentityService identityService;
  private final MeterRegistry meterRegistry;
  private final MongoTemplate mongoTemplate;

  public RelationshipService(
      RelationshipNodeRepository nodeRepository,
      RelationshipEdgeRepository edgeRepository,
      IdentityService identityService,
      MeterRegistry meterRegistry,
      MongoTemplate mongoTemplate) {
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.identityService = identityService;
    this.meterRegistry = meterRegistry;
    this.mongoTemplate = mongoTemplate;
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
    clearMembershipMemo();
  }

  /**
   * Return a user node's {@code memberOf} edges. Those of the request's own caller are read once
   * per HTTP request: authentication reads them before the request has an identity, and the walk
   * and every check the caller then makes see that one read, while the next request reads them
   * again, so a removed member is refused from the next request on. Every write this service makes
   * to an edge that can be a membership forgets them, so the request that changes a membership
   * sees its own change. Another user's memberships, and any read with no request active
   * (scheduler and engine threads), are read every time.
   */
  @SuppressWarnings("unchecked")
  private List<RelationshipEdgeEntity> memberships(String userNodeId) {
    String label = RelationshipLabel.MEMBER_OF.getLabel();
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return edgeRepository.findByFromAndLabel(userNodeId, label);
    }
    Map<String, List<RelationshipEdgeEntity>> memo =
        (Map<String, List<RelationshipEdgeEntity>>)
            attributes.getAttribute(MEMBERSHIP_MEMO_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (memo == null) {
      memo = new HashMap<>();
      attributes.setAttribute(MEMBERSHIP_MEMO_ATTRIBUTE, memo, RequestAttributes.SCOPE_REQUEST);
    }
    Token caller = identityService.getCurrentIdentity();
    // Before the request has an identity the read is kept for the caller authentication is about
    // to establish; once it has one, only that caller's own memberships are taken from the memo.
    boolean ownMemberships =
        caller != null
            && RelationshipType.USER.equals(identityRelationshipType(caller))
            && userNodeId.equals(RelationshipType.USER.getLabel() + ":" + caller.getPrincipal());
    List<RelationshipEdgeEntity> edges = (ownMemberships) ? memo.get(userNodeId) : null;
    if (edges == null) {
      edges = List.copyOf(edgeRepository.findByFromAndLabel(userNodeId, label));
      if (ownMemberships || caller == null) {
        memo.put(userNodeId, edges);
      }
    }
    return edges;
  }

  private void clearMembershipMemo() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.removeAttribute(MEMBERSHIP_MEMO_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
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
    clearMembershipMemo();
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
    clearMembershipMemo();
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
    clearMembershipMemo();
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
    clearMembershipMemo();
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
    List<NodeRef> found =
        reach(
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
          .findByFromInAndToIn(unwalked, found.stream().map(NodeRef::id).toList())
          .forEach(edge -> placement.place(edge.getFrom(), edge.getTo()));
    }
    for (NodeRef node : found) {
      // A node reached by two edges from one workspace is still one node.
      for (String workspace : placement.workspacesByChild.getOrDefault(node.id(), Set.of())) {
        refs.computeIfAbsent(workspace, w -> new ArrayList<>()).add(node.ref());
      }
    }
    return refs;
  }

  /**
   * The number of workflows in each of {@code workspaces} the current principal may see, keyed by
   * the workspace as named: what {@code refsInWorkspaces(WORKFLOW, workspaces)} would list, counted
   * on the edge index without reading a workflow. The workspaces counted are the ones that walk
   * descends from - every one named for a caller anchored at the root, a user's own among them,
   * a workspace token's own - and each count is that workspace's {@code hasWorkflow} edges. A
   * workspace with none is absent.
   */
  public Map<String, Long> workflowCountsInWorkspaces(Collection<String> workspaces) {
    Map<String, Long> counts = new HashMap<>();
    Anchor anchor =
        filterAnchor(
            identityService.getCurrentIdentity(), RelationshipType.WORKFLOW, Optional.empty());
    if (anchor == null) {
      return counts;
    }
    Map<String, String> workspaceByNodeId = new HashMap<>();
    resolveNodes(RelationshipType.WORKSPACE, workspaces)
        .forEach((workspace, node) -> workspaceByNodeId.put(node.getId(), workspace));
    List<String> reached =
        workspacesReached(anchor, List.copyOf(workspaces)).stream()
            .map(NodeRef::id)
            .filter(workspaceByNodeId::containsKey)
            .toList();
    if (reached.isEmpty()) {
      return counts;
    }
    // A workflow hangs from its one workspace by one hasWorkflow edge, so the edges counted are
    // the workflows listed. The count differs from the listing only on a graph that is already
    // inconsistent: an edge whose workflow node is gone, or doubled, still counts here.
    List<Document> pipeline =
        List.of(
            new Document(
                "$match",
                new Document("from", new Document("$in", reached))
                    .append("label", RelationshipLabel.HAS_WORKFLOW.getLabel())),
            new Document(
                "$group", new Document("_id", "$from").append("n", new Document("$sum", 1))));
    for (Document count : edges().aggregate(pipeline)) {
      counts.put(
          workspaceByNodeId.get(count.getString("_id")), ((Number) count.get("n")).longValue());
    }
    return counts;
  }

  // The named workspaces a walk from the anchor, confined to them, descends from: the starts of
  // findNodes for such a walk, and for a workspace token its own workspace when the names match it.
  private List<NodeRef> workspacesReached(Anchor anchor, List<String> workspaces) {
    if (RelationshipType.ROOT.equals(anchor.type())) {
      return resolveNodes(RelationshipType.WORKSPACE, workspaces).values().stream()
          .distinct()
          .map(NodeRef::of)
          .toList();
    }
    RelationshipNodeEntity from = resolveNode(anchor.type(), anchor.from()).orElse(null);
    if (from == null) {
      return List.of();
    }
    if (RelationshipType.USER.equals(anchor.type())) {
      return namedChildWorkspaces(NodeRef.of(from), workspaces, RelationshipType.WORKFLOW);
    }
    Optional<RelationshipType> workspaceType = Optional.of(RelationshipType.WORKSPACE);
    return matchesIntermediate(NodeRef.of(from), true, workspaceType, Optional.of(workspaces))
        ? List.of(NodeRef.of(from))
        : List.of();
  }

  /**
   * {@link #refsInWorkspace} with no names, as nodes: each carries its id, type, ref and slug and
   * no data.
   */
  public List<RelationshipNodeEntity> nodesInWorkspace(RelationshipType type, String workspace) {
    Anchor anchor = filterAnchor(identityService.getCurrentIdentity(), type, Optional.empty());
    if (anchor == null) {
      return new ArrayList<>();
    }
    return findNodes(
        anchor.type(),
        anchor.from(),
        type,
        anchor.toList(),
        Optional.of(RelationshipType.WORKSPACE),
        Optional.of(List.of(workspace)));
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
          reach(
                  anchor.type(),
                  anchor.from(),
                  toType,
                  anchor.toList(),
                  intermediateType,
                  intermediateList,
                  null)
              .stream()
              .map(returnSlugs ? NodeRef::slug : NodeRef::ref)
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
    return memberships("user:" + principal).stream()
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
   * that hold only for this shape (runsReached, namedChildWorkspaces, globalTasks,
   * workspacesUnderRoot, workflowCountsInWorkspaces, and a user's edges being its memberships): a
   * user reaches only workspaces and only by memberOf, only a workspace reaches a run, nothing
   * starts at a run or a workflow, and the root reaches tasks through hasTask. The labels a walk
   * follows are computed from these shapes (labelsToward), so a walk follows a new shape as soon
   * as it is listed. A new kind of edge is refused here until those lookups are checked against
   * it; add it with RelationshipLookupEquivalenceTest covering it.
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

  private record Shape(String from, String label, String to) {}

  private static final List<Shape> SHAPES =
      EDGE_SHAPES.stream()
          .map(shape -> shape.split("\\|"))
          .map(parts -> new Shape(parts[0], parts[1], parts[2]))
          .toList();

  /**
   * The labels of the edges that leave a node of any of {@code fromTypes} and lie on a path to a
   * node of {@code toType}, by {@link #EDGE_SHAPES}: an edge counts when it runs to the target type
   * or to a type the target is reached from. Empty when no shape leads there, so a walk for a type
   * nothing reaches reads nothing. Computed from the shapes, so a new shape is followed as soon as
   * it is allowed.
   */
  private static Set<String> labelsToward(Collection<String> fromTypes, String toType) {
    Set<String> leading = new HashSet<>(Set.of(toType));
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Shape shape : SHAPES) {
        if (leading.contains(shape.to()) && leading.add(shape.from())) {
          grew = true;
        }
      }
    }
    Set<String> labels = new HashSet<>();
    for (Shape shape : SHAPES) {
      if (fromTypes.contains(shape.from()) && leading.contains(shape.to())) {
        labels.add(shape.label());
      }
    }
    return labels;
  }

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
    clearMembershipMemo();
  }

  // ── Traversal ──────────────────────────────────────────────────────────────

  /*
   * Retrieve the node refs of type toType reachable from the given node
   */
  public List<String> findNodeRefs(
      RelationshipType fromType, String from, RelationshipType toType) {
    return reach(fromType, from, toType, Optional.empty(), Optional.empty(), Optional.empty(), null)
        .stream()
        .map(NodeRef::ref)
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
   * Return the ids of the nodes a node's outgoing edges with the label run to, from the edges
   * alone: no node is read, so an id may name a node that is gone.
   */
  public Set<String> childIdsByLabel(
      RelationshipType fromType, String from, RelationshipLabel label) {
    Set<String> ids = new HashSet<>();
    resolveNode(fromType, from)
        .ifPresent(
            node ->
                edges()
                    .find(new Document("from", node.getId()).append("label", label.getLabel()))
                    .projection(new Document("to", 1).append("_id", 0))
                    .forEach(edge -> ids.add(edge.getString("to"))));
    return ids;
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
   * workspace -> workflow|workflowrun|workspacetask|approvergroup|integration}, every workspace
   * hangs from the root, and nothing hangs below a run or a workflow, so these lookups read only
   * the edges that can lead to the target and find exactly the nodes the full walk finds:
   *
   * <ul>
   *   <li>A walk confined to named workspaces starts at those workspaces: from the root, every one
   *       that exists; from a user, those of the user's own workspaces it names.
   *   <li>Named runs are found from their own incoming edges: a run counts when its workspace is
   *       the anchor, or one the anchoring user reaches by an edge read now.
   *   <li>The global task catalogue is the root's {@code hasTask} children.
   *   <li>Every workspace, from the root, is the root's {@code contains} children of that type.
   *   <li>A walk level reads only the edges whose label lies on a path to the target type ({@link
   *       #labelsToward}): a walk for approver groups reads no workflow edge, and none but a walk
   *       for runs reads a {@code hasWorkflowRun} edge.
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
    return reach(fromType, from, toType, toList, intermediateType, intermediateList, null).stream()
        .map(NodeRef::entity)
        .collect(Collectors.toList());
  }

  /**
   * A node as a lookup carries it: id, type, ref and slug, read straight from the document. One
   * that was resolved or read whole keeps its entity, so {@link #findNodes} hands back the entity
   * it always did; the others become an entity without data only there.
   */
  private record NodeRef(
      String id, String type, String ref, String slug, RelationshipNodeEntity whole) {

    private static NodeRef of(RelationshipNodeEntity node) {
      return new NodeRef(node.getId(), node.getType(), node.getRef(), node.getSlug(), node);
    }

    private static NodeRef of(Document node) {
      return new NodeRef(
          node.getString("_id"),
          node.getString("type"),
          node.getString("ref"),
          node.getString("slug"),
          null);
    }

    private RelationshipNodeEntity entity() {
      if (whole != null) {
        return whole;
      }
      RelationshipNodeEntity node = new RelationshipNodeEntity(type, ref, slug, Optional.empty());
      node.setId(id);
      return node;
    }
  }

  /** An edge as a walk carries it: the ids of its two ends. */
  private record Ends(String from, String to) {}

  // findNodes as light nodes, recording into the placement, when there is one, the edges the walk
  // follows.
  private List<NodeRef> reach(
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
          resolveNodes(RelationshipType.WORKSPACE, intermediateList.get()).values().stream()
              .map(NodeRef::of)
              .toList(),
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
      return globalTasks(anchor, toList).stream().map(NodeRef::of).collect(Collectors.toList());
    }
    if (RelationshipType.ROOT.equals(fromType)
        && RelationshipType.WORKSPACE.equals(toType)
        && !hasIntermediate) {
      return workspacesUnderRoot(anchor, toList);
    }
    if (RelationshipType.WORKFLOWRUN.equals(toType)
        && toList.isPresent()
        && (!hasIntermediate || inWorkspaces)
        && (RelationshipType.USER.equals(fromType)
            || RelationshipType.WORKSPACE.equals(fromType))) {
      return runsReached(anchor, toList.get(), hasIntermediate, intermediateList).stream()
          .map(NodeRef::of)
          .collect(Collectors.toList());
    }
    List<NodeRef> starts =
        (RelationshipType.USER.equals(fromType) && inWorkspaces)
            ? namedChildWorkspaces(NodeRef.of(anchor), intermediateList.get(), toType)
            : List.of(NodeRef.of(anchor));
    return walk(
        starts, toType, toList, hasIntermediate, intermediateType, intermediateList, placement);
  }

  // The workspaces a user's node has an edge to that the names match: where a walk from the user,
  // confined to those workspaces, passes the confinement. The others lead to no node it finds.
  private List<NodeRef> namedChildWorkspaces(
      NodeRef user, List<String> workspaces, RelationshipType toType) {
    List<String> children =
        edgesFrom(List.of(user), toType).stream()
            .map(Ends::to)
            .filter(id -> !id.equals(user.id()))
            .distinct()
            .toList();
    return children.isEmpty()
        ? List.of()
        : nodes(
            new Document("_id", new Document("$in", children))
                .append("type", RelationshipType.WORKSPACE.getLabel())
                .append("$or", named(workspaces)));
  }

  // Every workspace under the root, optionally those of these refs or names: the root's contains
  // children of the type. Every workspace hangs from the root, so the users beside them, whose
  // memberships lead only to those same workspaces, are neither read nor walked.
  private List<NodeRef> workspacesUnderRoot(
      RelationshipNodeEntity root, Optional<List<String>> toList) {
    List<String> children =
        edgesFrom(List.of(NodeRef.of(root)), RelationshipType.WORKSPACE).stream()
            .map(Ends::to)
            .distinct()
            .toList();
    if (children.isEmpty()) {
      return new ArrayList<>();
    }
    Document filter =
        new Document("_id", new Document("$in", children))
            .append("type", RelationshipType.WORKSPACE.getLabel());
    toList.ifPresent(names -> filter.append("$or", named(names)));
    return nodes(filter);
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
          (!hasIntermediate
                  || matchesIntermediate(NodeRef.of(anchor), true, workspaceType, workspaces))
              ? Set.of(anchor.getId())
              : Set.of();
    } else {
      Set<String> parents =
          into.stream().map(RelationshipEdgeEntity::getFrom).collect(Collectors.toSet());
      // A user's edges are its memberships, which the request reads once.
      List<String> reached =
          memberships(anchor.getId()).stream()
              .map(RelationshipEdgeEntity::getTo)
              .filter(parents::contains)
              .toList();
      // A run is a leaf: the walk never goes on from one, so none leads to another.
      through =
          nodeRepository.findAllById(reached).stream()
              .filter(node -> !RelationshipType.WORKFLOWRUN.getLabel().equals(node.getType()))
              .filter(
                  node ->
                      !hasIntermediate
                          || matchesIntermediate(
                              NodeRef.of(node), true, workspaceType, workspaces))
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

  private MongoCollection<Document> edges() {
    return mongoTemplate.getCollection(mongoTemplate.getCollectionName(RelationshipEdgeEntity.class));
  }

  // Nodes matching the filter as id, type, ref and slug, read as documents: no entity is mapped.
  private List<NodeRef> nodes(Document filter) {
    List<NodeRef> nodes = new ArrayList<>();
    mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(RelationshipNodeEntity.class))
        .find(filter)
        .projection(new Document("type", 1).append("ref", 1).append("slug", 1))
        .forEach(node -> nodes.add(NodeRef.of(node)));
    return nodes;
  }

  // The slug or the ref is any of the names: the two branches of a node filter's $or.
  private static List<Document> named(Collection<String> names) {
    Document in = new Document("$in", List.copyOf(names));
    return List.of(new Document("slug", in), new Document("ref", in));
  }

  /*
   * A walk level's outgoing edges, as their two ends, read as documents on the from_label index:
   * only the edges whose label leads from these nodes toward the target type, so the others are
   * never read, and none at all when nothing leads there. A user's node alone is its memberships,
   * which the request has usually read already.
   */
  private List<Ends> edgesFrom(Collection<NodeRef> from, RelationshipType toType) {
    Set<String> labels =
        labelsToward(
            from.stream().map(NodeRef::type).collect(Collectors.toSet()), toType.getLabel());
    if (labels.isEmpty()) {
      return List.of();
    }
    List<Ends> ends = new ArrayList<>();
    if (from.size() == 1
        && RelationshipType.USER.getLabel().equals(from.iterator().next().type())
        && labels.equals(Set.of(RelationshipLabel.MEMBER_OF.getLabel()))) {
      memberships(from.iterator().next().id())
          .forEach(edge -> ends.add(new Ends(edge.getFrom(), edge.getTo())));
      return ends;
    }
    edges()
        .find(
            new Document("from", new Document("$in", from.stream().map(NodeRef::id).toList()))
                .append("label", new Document("$in", List.copyOf(labels))))
        .projection(new Document("from", 1).append("to", 1).append("_id", 0))
        .forEach(edge -> ends.add(new Ends(edge.getString("from"), edge.getString("to"))));
    return ends;
  }

  // A walk level's nodes as type, ref and slug. A node of the target type that none of the names
  // match is neither found nor walked past, so a walk for named nodes leaves it unread.
  private List<NodeRef> readLevel(
      Collection<String> ids, RelationshipType toType, Optional<List<String>> toList) {
    Document filter = new Document("_id", new Document("$in", List.copyOf(ids)));
    if (toList.isPresent()) {
      List<Document> wanted = new ArrayList<>();
      wanted.add(new Document("type", new Document("$ne", toType.getLabel())));
      wanted.addAll(named(toList.get()));
      filter.append("$or", wanted);
    }
    return nodes(filter);
  }

  // The level-by-level downward walk of findNodes, from one or more start nodes.
  private List<NodeRef> walk(
      List<NodeRef> starts,
      RelationshipType toType,
      Optional<List<String>> toList,
      boolean hasIntermediate,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList,
      Placement placement) {
    String toTypeLabel = toType.getLabel();
    List<NodeRef> results = new ArrayList<>();
    Map<String, NodeRef> levelNodes = new HashMap<>();
    Map<String, Boolean> levelPassed = new HashMap<>();
    for (NodeRef start : starts) {
      levelNodes.put(start.id(), start);
      levelPassed.put(
          start.id(),
          matchesIntermediate(start, hasIntermediate, intermediateType, intermediateList));
    }
    Set<String> seen = new HashSet<>(levelNodes.keySet());

    while (!levelNodes.isEmpty()) {
      List<NodeRef> expand = new ArrayList<>();
      for (Map.Entry<String, NodeRef> entry : levelNodes.entrySet()) {
        NodeRef node = entry.getValue();
        if (node.type().equals(toTypeLabel)) {
          boolean interOk = !hasIntermediate || levelPassed.getOrDefault(entry.getKey(), false);
          if (interOk && matchesToList(node, toList)) {
            results.add(node);
          }
          // Target nodes are the leaves we want; do not expand past them.
        } else {
          expand.add(node);
        }
      }
      if (expand.isEmpty()) {
        break;
      }
      if (placement != null) {
        expand.forEach(node -> placement.expanded.add(node.id()));
      }

      List<Ends> edges = edgesFrom(expand, toType);
      if (edges.isEmpty()) {
        break;
      }

      Map<String, Boolean> childPassed = new HashMap<>();
      for (Ends edge : edges) {
        String childId = edge.to();
        if (placement != null) {
          placement.place(edge.from(), childId);
        }
        if (seen.contains(childId)) {
          continue;
        }
        boolean parentPassed = levelPassed.getOrDefault(edge.from(), false);
        childPassed.merge(childId, parentPassed, Boolean::logicalOr);
      }
      if (childPassed.isEmpty()) {
        break;
      }

      Map<String, NodeRef> nextNodes = new HashMap<>();
      Map<String, Boolean> nextPassed = new HashMap<>();
      for (NodeRef child : readLevel(childPassed.keySet(), toType, toList)) {
        boolean passed =
            childPassed.getOrDefault(child.id(), false)
                || matchesIntermediate(child, hasIntermediate, intermediateType, intermediateList);
        nextNodes.put(child.id(), child);
        nextPassed.put(child.id(), passed);
        seen.add(child.id());
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
        !reach(fromType, from, toType, toList, intermediateType, intermediateList, null).isEmpty();
    LOGGER.debug("Has Node(s): {}", has);
    return has;
  }

  private static boolean matchesToList(NodeRef node, Optional<List<String>> toList) {
    return toList.isEmpty()
        || toList.get().contains(node.ref())
        || toList.get().contains(node.slug());
  }

  private static boolean matchesIntermediate(
      NodeRef node,
      boolean hasIntermediate,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    if (!hasIntermediate) {
      return false;
    }
    if (!node.type().equals(intermediateType.get().getLabel())) {
      return false;
    }
    return intermediateList.get().contains(node.ref())
        || intermediateList.get().contains(node.slug());
  }
}
