package io.boomerang.core.security.enums;

import java.util.HashMap;
import java.util.Map;

/*
 * H14-c (DD-01 wire-name sweep): TEAM's label became "workspace". Stored permission strings
 * (roles.permissions[], tokens.permissions[].actions[]) are migrated by loader changeunit
 * _0016__WorkspaceRename. "team" stays accepted as an input alias (BY_LABEL below) so any
 * not-yet-migrated/legacy caller still resolves, matching AuthScope/RelationshipType's DD-01
 * alias pattern.
 */
public enum PermissionResource {
  SYSTEM("system"),
  WORKFLOW("workflow"),
  WORKFLOWRUN("workflowrun"),
  WORKFLOWTEMPLATE("workflowtemplate"),
  TASKRUN("taskrun"),
  TASK("task"),
  ACTION("action"),
  USER("user"),
  WORKSPACE("workspace"),
  TOKEN("token"),
  PARAMETER("parameter"),
  SCHEDULE("schedule"),
  INSIGHTS("insights"),
  INTEGRATION("integration"),
  WEBHOOK("webhook"),
  ANY("**");

  private String label;

  private static final Map<String, PermissionResource> BY_LABEL = new HashMap<>();

  PermissionResource(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
    for (PermissionResource e : values()) {
      BY_LABEL.put(e.label, e);
    }
    // DD-01 deprecation alias: "team" input resolves to the renamed WORKSPACE resource.
    BY_LABEL.put("team", WORKSPACE);
  }

  public static PermissionResource valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
