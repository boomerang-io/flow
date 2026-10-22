package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;

public enum RelationshipType {
  ROOT("root"),
  TEAM("team"),
  USER("user"),
  WORKFLOW("workflow"),
  WORKFLOWRUN("workflowrun"),
  APPROVERGROUP("approvergroup"),
  //  TEMPLATE("template"),
  //  TOKEN("token"),
  INTEGRATION("integration"),
  SCHEDULE("schedule"),
  TEAMTASK("teamtask"),
  TASK("task");

  private String label;

  private static final Map<String, RelationshipType> BY_LABEL = new HashMap<>();

  RelationshipType(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
    for (RelationshipType e : values()) {
      BY_LABEL.put(e.label, e);
    }
  }

  public static RelationshipType valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
