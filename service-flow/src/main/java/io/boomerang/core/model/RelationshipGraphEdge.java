package io.boomerang.core.model;

import org.jgrapht.graph.DefaultEdge;

/**
 * Represents an edge in the relationship graph with the extra metadata
 *
 * <p>DO NOT use the Lombok Data annotation
 */
public class RelationshipGraphEdge extends DefaultEdge {
  private String label;
  private String role;

  public RelationshipGraphEdge(String label, String role) {
    super(); // Call the constructor of DefaultEdge
    this.label = label;
    this.role = role;
  }

  public String getLabel() {
    return label;
  }

  public String getRole() {
    return role;
  }

  @Override
  public String toString() {
    return super.toString()
        + "GraphEdge{"
        + "label='"
        + label
        + '\''
        + ", role='"
        + role
        + '\''
        + '}';
  }
}
