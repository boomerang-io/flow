package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * The Workspace types the agent provisions PVCs for: one per Workflow (persists across
 * executions) and one per WorkflowRun (torn down when the run terminates).
 */
public enum WorkspaceType {
  workflow("workflow"),
  workflowRun("workflowrun");

  private final String label;

  WorkspaceType(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  public static Optional<WorkspaceType> fromLabel(String label) {
    return Arrays.stream(values()).filter(type -> type.label.equalsIgnoreCase(label)).findFirst();
  }
}
