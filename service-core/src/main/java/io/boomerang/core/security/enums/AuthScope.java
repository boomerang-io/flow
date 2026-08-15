package io.boomerang.core.security.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

/*
 * Remains lowercase to match TokenTypePrefix and what a user would enter in json
 *
 * DD-01 (Team -> Workspace rename): the "team" constant/label became "workspace". "team" stays
 * accepted as an input alias (BY_LABEL below, and valueOfLabel() is also the @JsonCreator so any
 * caller/token still sending the pre-rename scope value keeps resolving) but the wire/label
 * output is always "workspace" (getLabel() is the @JsonValue).
 */
public enum AuthScope {
  session("session"),
  user("user"),
  workspace("workspace"),
  workflow("workflow"),
  global("global");

  private String label;

  private static final Map<String, AuthScope> BY_LABEL = new HashMap<>();

  AuthScope(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  static {
    for (AuthScope e : values()) {
      BY_LABEL.put(e.label, e);
    }
    // DD-01 deprecation alias: "team" input resolves to the renamed "workspace" scope.
    BY_LABEL.put("team", workspace);
  }

  @JsonCreator
  public static AuthScope valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
