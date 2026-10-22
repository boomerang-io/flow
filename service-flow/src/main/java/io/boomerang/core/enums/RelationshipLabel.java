package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;

/*
 * Relationship Label
 *
 * Ref:
 * - https://learn.microsoft.com/en-us/azure/cosmos-db/gremlin/modeling#relationship-labels
 * - https://tinkerpop.apache.org/docs/3.6.2/recipes/
 */
public enum RelationshipLabel {
  CONTAINS("contains"),
  OWNER_OF("ownerOf"),
  MEMBER_OF("memberOf"),
  HAS_INTEGRATION("hasIntegration"),
  HAS_WORKFLOW("hasWorkflow"),
  HAS_WORKFLOWRUN("hasWorkflowRun"),
  HAS_TASK("hasTask"),
  HAS_TASKRUN("hasTaskRun"),
  HAS_APPROVER_GROUP("hasApproverGroup");

  private String label;

  private static final Map<String, RelationshipLabel> BY_LABEL = new HashMap<>();

  RelationshipLabel(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
    for (RelationshipLabel e : values()) {
      BY_LABEL.put(e.label, e);
    }
  }

  public static RelationshipLabel valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
