package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;

public enum RoleEnum {
  OWNER("owner"),
  EDITOR("editor"),
  READER("reader");

  private String label;

  private static final Map<String, RoleEnum> BY_LABEL = new HashMap<>();

  RoleEnum(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
    for (RoleEnum e : values()) {
      BY_LABEL.put(e.label, e);
    }
  }

  public static RoleEnum valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }

  public static boolean hasLabel(String label) {
    return BY_LABEL.containsKey(label);
  }
}
