package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;

// The former TEAM type is labelled "workspace". No stored rel_nodes/rel_edges "type" value says
// "team": the migrations write workspace nodes from the start (_0004__SeedSystemWorkspace,
// _0013__V3BuildRelationshipGraph), so there is no "team" input alias.
// TEAMTASK ("teamtask") keeps its name - it is not itself a workspace node type, it is the type of
// a task scoped to a workspace.
public enum RelationshipType {
  ROOT("root"),
  WORKSPACE("workspace"),
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
