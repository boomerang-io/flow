package io.boomerang.security.enums;

import java.util.HashMap;
import java.util.Map;

public enum PermissionAction {
  READ("read"),
  WRITE("write"),
  DELETE("delete"),
  ACTION("action");

  private String label;

  private static final Map<String, PermissionAction> BY_LABEL = new HashMap<>();

  PermissionAction(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
    for (PermissionAction e : values()) {
      BY_LABEL.put(e.label, e);
    }
  }

  public static PermissionAction valueOfLabel(String label) {
    return BY_LABEL.get(label.toLowerCase());
  }
}
