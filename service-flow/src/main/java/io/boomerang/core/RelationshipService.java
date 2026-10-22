package io.boomerang.core;

import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.RelationshipGraphEdge;
import io.boomerang.core.model.ResolvedPermissions;
import io.boomerang.core.model.Token;
import io.boomerang.core.repository.RelationshipEdgeRepository;
import io.boomerang.core.repository.RelationshipNodeRepository;
import io.boomerang.security.IdentityService;
import io.boomerang.security.enums.AuthScope;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RelationshipService {

  private static final Logger LOGGER = LogManager.getLogger();

  private RelationshipGraph graphCache = RelationshipGraph.getInstance();
  private final RelationshipNodeRepository nodeRepository;
  private final RelationshipEdgeRepository edgeRepository;
  private final IdentityService identityService;

  // TODO make sure the graph should be initialised here or better on start of service.
  // How many times does this service get created?
  public RelationshipService(
      RelationshipNodeRepository nodeRepository,
      RelationshipEdgeRepository edgeRepository,
      IdentityService identityService) {
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.identityService = identityService;
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

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
  @Transactional
  public void createEdge(
      RelationshipType fromType,
      String from,
      RelationshipLabel label,
      RelationshipType toType,
      String to,
      Optional<Map<String, String>> data) {
    RelationshipNodeEntity fromResult =
        this.getNodeFromGraph(fromType, from, graphCache.getGraph());
    RelationshipNodeEntity toResult = this.getNodeFromGraph(toType, to, graphCache.getGraph());
    if (Objects.isNull(fromResult) || Objects.isNull(toResult)) {
      throw new IllegalArgumentException("Node does not exist");
    }
    edgeRepository.save(
        new RelationshipEdgeEntity(fromResult.getId(), label, toResult.getId(), data));
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Creates the Relationship Edge for the current principal
   */
  @Transactional
  public void createEdge(RelationshipType toType, String to, Optional<Map<String, String>> data) {
    Token identity = identityService.getCurrentIdentity();
    LOGGER.debug("Creating edge for: {}", identity.getPrincipal());
    RelationshipType fromType = null;
    if (AuthScope.session.equals(identity.getType())) {
      fromType = RelationshipType.USER;
    } else {
      fromType = RelationshipType.valueOfLabel(identity.getType().getLabel());
    }
    this.createEdge(
        fromType, identity.getPrincipal(), RelationshipLabel.MEMBER_OF, toType, to, data);
  }

  /*
   * Creates the Relationship Node mapped to an object in the system
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
    RelationshipNodeEntity fromResult =
        this.getNodeFromGraph(fromType, from, graphCache.getGraph());
    if (Objects.isNull(fromResult)) {
      throw new IllegalArgumentException("From node does not exist");
    }
    RelationshipNodeEntity toNode = this.createNode(toType, toRef, toSlug, nodeData);
    edgeRepository.save(
        new RelationshipEdgeEntity(fromResult.getId(), label, toNode.getId(), edgeData));
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Update the Relationship Edge's data
   */
  @Transactional
  public void updateEdgeData(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      String to,
      Map<String, String> data)
      throws IllegalArgumentException {
    RelationshipNodeEntity fromNode = this.getNodeFromGraph(fromType, from, graphCache.getGraph());
    RelationshipNodeEntity toNode = this.getNodeFromGraph(toType, to, graphCache.getGraph());
    edgeRepository.updateDataByFromAndTo(
        fromNode.getType() + ":" + fromNode.getRef(),
        toNode.getType() + ":" + toNode.getRef(),
        data);
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Update the Relationship Edge's data for current principal
   */
  @Transactional
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
  @Transactional
  public void removeEdge(RelationshipType fromType, String from, RelationshipType toType, String to)
      throws IllegalArgumentException {
    RelationshipNodeEntity fromNode = this.getNodeFromGraph(fromType, from, graphCache.getGraph());
    RelationshipNodeEntity toNode = this.getNodeFromGraph(toType, to, graphCache.getGraph());
    edgeRepository.deleteByFromAndTo(
        fromNode.getType() + ":" + fromNode.getRef(), toNode.getType() + ":" + toNode.getRef());
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Removes the Relationship Edge for current Principal
   */
  @Transactional
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
  @Transactional
  public void removeNodeAndEdgeByRefOrSlug(RelationshipType type, String refOrSlug) {
    RelationshipNodeEntity node = nodeRepository.deleteByRefOrSlug(type.getLabel(), refOrSlug);
    edgeRepository.deleteByFromOrTo(node.getId());
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Removes the Relationship Node By Ref and all Edges linked to it
   */
  @Transactional
  public void removeNodeAndEdgeByRef(RelationshipType type, String ref) {
    RelationshipNodeEntity node = nodeRepository.deleteByTypeAndRef(type.getLabel(), ref);
    edgeRepository.deleteByFromOrTo(node.getId());
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Removes the Relationship Node and all Edges linked to it
   */
  @Transactional
  public void updateNodeByRefOrSlug(RelationshipType type, String refOrSlug, String newSlug) {
    nodeRepository.updateSlugByTypeAndRefOrSlug(type.getLabel(), refOrSlug, newSlug);
    this.graphCache.buildGraph(nodeRepository.findAll(), edgeRepository.findAll());
  }

  /*
   * Checks if the slug or ref exists for the Relationship Type. It does not filter for Relationship - can probably only be used for top level nodes
   */
  public boolean doesSlugOrRefExistForType(RelationshipType type, String refOrSlug) {
    LOGGER.debug("Checking {}:{} for existence", type.getLabel(), refOrSlug);
    try {
      this.getNodeFromGraph(type, refOrSlug, graphCache.getGraph());
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /*
   * Retrieves the ref for the Node by type and slug or ref
   *
   * This should only be used from unique ID to return slug. Otherwise multiples for the wrong team could be returned.
   *
   * TODO: make this better
   */
  public String getSlugByRefForType(RelationshipType type, String refOrSlug) {
    LOGGER.debug("Retrieving ref for {}:{}", type.getLabel(), refOrSlug);
    RelationshipNodeEntity node = this.getNodeFromGraph(type, refOrSlug, graphCache.getGraph());
    if (Objects.isNull(node)) {
      throw new IllegalArgumentException("Node does not exist");
    }
    return node.getSlug();
  }

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
    List<String> refs = new ArrayList<>();
    Token identity = identityService.getCurrentIdentity();
    String principal = identity.getPrincipal();
    checkPermissions(identity.getPermissions(), type, toList);
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
      case team:
        return hasNodes(
            RelationshipType.TEAM,
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
            .map(permission -> permission.getPrincipal())
            .collect(Collectors.toList());
    if (flattenedPermissionPrincipals.containsAll(toList)) {
      return true;
    }
    return false;
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

    // Check for special cases first
    if (toType.equals(RelationshipType.TASK)) {
      LOGGER.warn("Special case for Tasks available to all users");
      // All users have access to all tasks
      fromType = RelationshipType.ROOT;
      from = "root";
      //    } else if (toType.equals(RelationshipType.TEAM)
      //        && identity.getPermissions().contains("**/**")) {
      //      // TODO allow for now. need to update permissions to have assignedScope and correct
      // permission
      //      // strings
      //      fromType = RelationshipType.ROOT;
      //      from = "root";
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
            LOGGER.warn("Special case for Workflow Tokens trying to access itself");
            // Special case for Workflow Tokens trying to access itself i.e. workflow -> team ->
            // workflow
            toRefsOrSlugs = Optional.of(List.of(from));
            fromType = RelationshipType.ROOT;
            from = "root";
          }
          break;
        case team:
          fromType = RelationshipType.TEAM;
          break;
        case global:
          // Allow anything with no filtering
          // Retrieve all nodes of a type in the system.
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

  /*
   * Retrieve the Parent by Label
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
   * Retrieve the Space and Roles for current Principal
   */
  public Map<String, String> roles(String principal) {
    List<RelationshipEdgeEntity> edges =
        edgeRepository.findByFromAndLabel(
            "user:" + principal, RelationshipLabel.MEMBER_OF.getLabel());
    return edges.stream()
        .collect(
            Collectors.toMap(
                e -> e.getTo().split(":")[1],
                e -> e.getData().containsKey("role") ? e.getData().get("role") : "viewer"));
  }

  /*
   * Retrieve the Members and Roles for a Workspace
   */
  public Map<String, String> membersAndRoles(String space) {
    Graph<RelationshipNodeEntity, RelationshipGraphEdge> graph = graphCache.getGraph();
    RelationshipNodeEntity rootNode = getNodeFromGraph(RelationshipType.TEAM, space, graph);
    // Find the user edges connected to the rootNode and return the role
    BFSShortestPath<RelationshipNodeEntity, RelationshipGraphEdge> bfs =
        new BFSShortestPath<>(graph);
    return graph.edgeSet().stream()
        .filter(edge -> bfs.getPath(graph.getEdgeTarget(edge), rootNode) != null)
        .filter(edge -> graph.getEdgeSource(edge).getType().equals("user"))
        .collect(
            Collectors.toMap(
                e -> graph.getEdgeSource(e).getRef(),
                e -> e.getRole().isEmpty() ? "viewer" : e.getRole()));
  }

  /*
   * Retrieve the nodes in the graph filtered by their relationships(edges)
   */
  public List<String> findNodeRefs(
      RelationshipType fromType, String from, RelationshipType toType) {
    return findNodes(fromType, from, toType, Optional.empty(), Optional.empty(), Optional.empty())
        .stream()
        .map(RelationshipNodeEntity::getRef)
        .collect(Collectors.toList());
  }

  /*
   * Retrieve the nodes in the graph filtered by their relationships(edges)
   */
  public List<RelationshipNodeEntity> findNodes(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    Graph<RelationshipNodeEntity, RelationshipGraphEdge> graph = graphCache.getGraph();
    BFSShortestPath<RelationshipNodeEntity, RelationshipGraphEdge> bfs =
        new BFSShortestPath<>(graph);
    try {
      RelationshipNodeEntity rootNode = getNodeFromGraph(fromType, from, graph);
      LOGGER.debug("Root Node: {}", rootNode.toString());
      if (intermediateType.isPresent() && intermediateList.isPresent()) {
        LOGGER.debug(
            "Intermediate Type: {}, List: {}", intermediateType.get(), intermediateList.get());
      }
      // Find the nodes connected to the rootNode
      List<RelationshipNodeEntity> nodes =
          graph.vertexSet().stream()
              .filter(node -> node.getType().equals(toType.getLabel()))
              .filter(node -> bfs.getPath(rootNode, node) != null)
              .filter(
                  node ->
                      toList.isEmpty()
                          || toList.get().contains(node.getRef())
                          || toList.get().contains(node.getSlug()))
              .filter(
                  node ->
                      intermediateType.isEmpty()
                          || intermediateList.isEmpty()
                          || bfs.getPath(rootNode, node).getVertexList().stream()
                              .filter(n -> n.getType().equals(intermediateType.get().getLabel()))
                              .anyMatch(
                                  n ->
                                      intermediateList.get().contains(n.getRef())
                                          || intermediateList.get().contains(n.getSlug())))
              .collect(Collectors.toList());
      LOGGER.debug("Found Node(s)[{}]: {}", nodes.size(), nodes.toString());
      return nodes;
    } catch (IllegalArgumentException e) {
      LOGGER.error("Root node not found", e);
    }
    return new ArrayList<>();
  }

  public boolean hasNodes(
      RelationshipType fromType,
      String from,
      RelationshipType toType,
      Optional<List<String>> toList,
      Optional<RelationshipType> intermediateType,
      Optional<List<String>> intermediateList) {
    Graph<RelationshipNodeEntity, RelationshipGraphEdge> graph = graphCache.getGraph();
    try {
      RelationshipNodeEntity rootNode = getNodeFromGraph(fromType, from, graph);
      // Find the nodes connected to the rootNode
      BFSShortestPath<RelationshipNodeEntity, RelationshipGraphEdge> bfs =
          new BFSShortestPath<>(graph);
      boolean has =
          graph.vertexSet().stream()
              .filter(node -> node.getType().equals(toType.getLabel()))
              .filter(node -> bfs.getPath(rootNode, node) != null)
              .filter(
                  node ->
                      toList.isEmpty()
                          || toList.get().contains(node.getRef())
                          || toList.get().contains(node.getSlug()))
              .filter(
                  node ->
                      intermediateType.isEmpty()
                          || intermediateList.isEmpty()
                          || bfs.getPath(rootNode, node).getVertexList().stream()
                              .filter(n -> n.getType().equals(intermediateType.get().getLabel()))
                              .anyMatch(
                                  n ->
                                      intermediateList.get().contains(n.getRef())
                                          || intermediateList.get().contains(n.getSlug())))
              .findAny()
              .isPresent();
      LOGGER.debug("Has Node(s): {}", has);
      return has;
    } catch (IllegalArgumentException e) {
      LOGGER.error("hasNodes() - Exception: {}", e.getMessage());
      return false;
    }
  }

  private RelationshipNodeEntity getNodeFromGraph(
      RelationshipType type,
      String refOrSlug,
      Graph<RelationshipNodeEntity, RelationshipGraphEdge> graph) {
    return graph.vertexSet().stream()
        .filter(
            node ->
                node.getType().equals(type.getLabel()) && (node.getRef().equals(refOrSlug))
                    || node.getSlug().equals(refOrSlug))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Node not found"));
  }
}
