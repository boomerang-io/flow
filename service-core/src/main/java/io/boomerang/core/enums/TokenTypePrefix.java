package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

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

  // ARCHIE pattern (T6-1): a cheap, pre-DB shape gate — a bearer that doesn't even look like a
  // Flow token never reaches Mongo. Covers every prefix above: g/t/w/u/s.
  private static final Pattern TOKEN_PATTERN = Pattern.compile("^bf[gtwus]_.+");

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

  /** True if {@code token} is shaped like a Flow-minted token (any {@code bf?_...} prefix). */
  public static boolean isFlowToken(String token) {
    return token != null && TOKEN_PATTERN.matcher(token).matches();
  }
}
