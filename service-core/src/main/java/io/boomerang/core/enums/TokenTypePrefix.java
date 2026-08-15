package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;

// DD-01 (Team -> Workspace rename): the constant is now "workspace", keeping the "bft" prefix
// string as-is - existing tokens carry bft_ and must keep resolving; changing the prefix string
// itself is a separate, future decision (not part of this rename).
public enum TokenTypePrefix {
  global("bfg"),
  workspace("bft"),
  workflow("bfw"),
  user("bfu"),
  session("bfs");

  public final String prefix;

  private static final Map<String, TokenTypePrefix> BY_PREFIX = new HashMap<>();

  private TokenTypePrefix(String prefix) {
    this.prefix = prefix;
  }

  public String getPrefix() {
    return prefix;
  }

  static {
    for (TokenTypePrefix e : values()) {
      BY_PREFIX.put(e.prefix, e);
    }
  }

  public static TokenTypePrefix valueOfPrefix(String prefix) {
    return BY_PREFIX.get(prefix);
  }
}
