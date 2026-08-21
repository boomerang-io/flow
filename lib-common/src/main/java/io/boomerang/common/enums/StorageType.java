package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * The storage (workflow-model "workspace") scopes the agent provisions PVCs for: one per Workflow (persists across
 * executions) and one per WorkflowRun (torn down when the run terminates).
 */
public enum StorageType {
  workflow("workflow"),
  workflowRun("workflowrun");

  private final String label;

  StorageType(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  public static Optional<StorageType> fromLabel(String label) {
    return Arrays.stream(values()).filter(type -> type.label.equalsIgnoreCase(label)).findFirst();
  }
}
