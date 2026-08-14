package io.boomerang.security.enums;

import java.util.HashMap;
import java.util.Map;

public enum PermissionResource {
  SYSTEM("system"),
  WORKFLOW("workflow"),
  WORKFLOWRUN("workflowrun"),
  WORKFLOWTEMPLATE("workflowtemplate"),
  TASKRUN("taskrun"),
  TASK("task"),
  ACTION("action"),
  USER("user"),
  TEAM("team"),
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
  }

  public static PermissionResource valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
