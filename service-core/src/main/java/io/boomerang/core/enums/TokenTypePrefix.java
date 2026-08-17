package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * T6-3: four prefixes, one per AuthScope token class - and ONLY these four. Per the maintainer's
 * ruling (specifications/merge-execution-plan.md, T6-3), the retired classes' legacy prefixes
 * ("bft" workspace, "bfw" workflow) are dropped ENTIRELY, with no deprecation window: a raw token
 * minted under either legacy prefix can never authenticate again (the SHA-256 hash of the full
 * raw token, prefix included, is all that is ever stored - see TokenService#hashString/validate -
 * so there is nothing to migrate the raw token itself to). Operators holding one of those tokens
 * must re-issue. TOKEN_PATTERN (the pre-DB shape gate) matches only "g", "k", "u", "s" - a "bft_"
 * or "bfw_" bearer fails this cheap gate exactly like any non-Flow bearer, never reaching Mongo.
 *
 * "bfk" ("worKspace" - kept from H14-e; K is the first letter in "workspace" not already claimed
 * by another prefix) is now the CLASS prefix for {@code AuthScope.key} - the renamed/generalised
 * successor of the old {@code workspace} constant, and also covers what used to be minted under
 * {@code workflow}/"bfw" (a {@code key} token with {@code actorKind=WORKFLOW}).
 */
public enum TokenTypePrefix {
  global("bfg"),
  key("bfk"),
  user("bfu"),
  session("bfs");

  public final String prefix;

  private static final Map<String, TokenTypePrefix> BY_PREFIX = new HashMap<>();

  // ARCHIE pattern (T6-1): a cheap, pre-DB shape gate — a bearer that doesn't even look like a
  // Flow token never reaches Mongo. Covers exactly the four live prefixes above (g/k/u/s) - the
  // retired "t"/"w" prefixes are deliberately NOT matched (T6-3: no deprecation window).
  private static final Pattern TOKEN_PATTERN = Pattern.compile("^bf[gkus]_.+");

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

  /** Null for any retired ("bft"/"bfw") or unknown prefix - callers must treat that as unknown. */
  public static TokenTypePrefix valueOfPrefix(String prefix) {
    return BY_PREFIX.get(prefix);
  }

  /** True if {@code token} is shaped like a Flow-minted token (any live {@code bf?_...} prefix). */
  public static boolean isFlowToken(String token) {
    return token != null && TOKEN_PATTERN.matcher(token).matches();
  }
}
