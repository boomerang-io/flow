package io.boomerang.core;

import io.boomerang.core.entity.RelationshipEdgeEntity;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.model.RelationshipGraphEdge;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;

public class RelationshipGraph {

  private static final Logger LOGGER = LogManager.getLogger();
  private static RelationshipGraph instance;
  private Graph<RelationshipNodeEntity, RelationshipGraphEdge> graph;

  private RelationshipGraph() {
    graph = new DefaultDirectedGraph<>(RelationshipGraphEdge.class);
  }

  public static synchronized RelationshipGraph getInstance() {
    if (instance == null) {
      instance = new RelationshipGraph();
    }
    return instance;
  }

  public synchronized Graph<RelationshipNodeEntity, RelationshipGraphEdge> getGraph() {
    return graph;
  }

  public synchronized void buildGraph(
      List<RelationshipNodeEntity> nodes, List<RelationshipEdgeEntity> edges) {
    LOGGER.debug("Building graph with {} nodes and {} edges", nodes.size(), edges.size());
    graph = new DefaultDirectedGraph<>(RelationshipGraphEdge.class);

    // Add nodes to the graph
    Map<String, RelationshipNodeEntity> nodeMap =
        nodes.stream().collect(Collectors.toMap(RelationshipNodeEntity::getId, node -> node));
    nodeMap.values().forEach(graph::addVertex);

    // Add edges to the graph
    for (RelationshipEdgeEntity edge : edges) {
      RelationshipNodeEntity fromNode = nodeMap.get(edge.getFrom());
      RelationshipNodeEntity toNode = nodeMap.get(edge.getTo());
      if (fromNode != null && toNode != null) {
        graph.addEdge(
            fromNode,
            toNode,
            new RelationshipGraphEdge(edge.getLabel(), edge.getData().get("role")));
      } else {
        LOGGER.warn("Edge not added: {} -> {}", edge.getFrom(), edge.getTo());
      }
    }
  }

  public synchronized void invalidateCache() {
    graph = new DefaultDirectedGraph<>(RelationshipGraphEdge.class);
  }
}
