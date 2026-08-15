package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;

// DD-01 (Team -> Workspace rename): TEAM's label became "workspace"; the stored rel_nodes/
// rel_edges "type" values are migrated by loader changeunit _0012__WorkspaceRename. "team"
// stays accepted as an input alias (BY_LABEL below) so any not-yet-migrated/legacy caller still
// resolves, but the label emitted (getLabel(), and therefore any *new* write) is "workspace".
// TEAMTASK ("teamtask") is left as-is - it is not itself a Team/Workspace node type, it is the
// Task-scoped-to-a-workspace relationship type and is not in DD-01's declared rename scope.
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
    // DD-01 deprecation alias: "team" input resolves to the renamed WORKSPACE type.
    BY_LABEL.put("team", WORKSPACE);
  }

  public static RelationshipType valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
