package io.boomerang.security.enums;

import java.util.HashMap;
import java.util.Map;

/*
 * Remains lowercase to match TokenTypePrefix and what a user would enter in json
 */
public enum AuthScope {
  session("session"),
  user("user"),
  team("team"),
  workflow("workflow"),
  global("global");

  private String label;

  private static final Map<String, AuthScope> BY_LABEL = new HashMap<>();

  AuthScope(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
    for (AuthScope e : values()) {
      BY_LABEL.put(e.label, e);
    }
  }

  public static AuthScope valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
