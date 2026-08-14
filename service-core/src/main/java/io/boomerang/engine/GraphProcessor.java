package io.boomerang.util;

import java.util.LinkedList;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm.SingleSourcePaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;

public class GraphProcessor {

  private GraphProcessor() {

  }

  public static Graph<String, DefaultEdge> createGraph(List<String> vertices,
      List<Pair<String, String>> edges) {
    Graph<String, DefaultEdge> g;
    g = new DefaultDirectedGraph<>(DefaultEdge.class);
    for (final String vertex : vertices) {
      g.addVertex(vertex);
    }
    for (final Pair<String, String> edge : edges) {
      if (!(edge.getLeft() == null || edge.getRight() == null)) {
        g.addEdge(edge.getLeft(), edge.getRight());
      }
    }

    return g;

  }

  public static List<String> createOrderedTaskList(Graph<String, DefaultEdge> g, String start,
      String end) {

    final List<String> orderedVertexList = new LinkedList<>();

    final DijkstraShortestPath<String, DefaultEdge> dijkstraAlg = new DijkstraShortestPath<>(g);
    TopologicalOrderIterator<String, DefaultEdge> orderIterator;

    orderIterator = new TopologicalOrderIterator<>(g);
    while (orderIterator.hasNext()) {
      final String vert = orderIterator.next();
      if (!(vert.equals(start) || vert.equals(end))) {
        final SingleSourcePaths<String, DefaultEdge> pathToEnd = dijkstraAlg.getPaths(vert);
        final SingleSourcePaths<String, DefaultEdge> pathFromStart = dijkstraAlg.getPaths(start);

        final boolean canReachEnd = (pathToEnd.getPath(end) != null);
        final boolean startToVertex = (pathFromStart.getPath(vert) != null);

        if (canReachEnd && startToVertex) {
          orderedVertexList.add(vert);
        }
      }
    }
    return orderedVertexList;
  }
}
