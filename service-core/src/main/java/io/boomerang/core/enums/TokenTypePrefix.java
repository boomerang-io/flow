package io.boomerang.core.enums;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * DD-01 (Team -> Workspace rename): the constant is now "workspace". H14-e finishes the rename by
 * moving the wire prefix off "bft" too - "bft" was the last visible "team" fossil (DD-01 kept it
 * on purpose; see specifications/merge-execution-plan.md).
 *
 * New prefix: "bfk" ("worKspace" - K is the first letter in "workspace" not already claimed by
 * another prefix: g=global, t=[legacy team], w=workflow, u=user, s=session; "bfw" is taken by
 * workflow so the obvious "w" was unavailable). New tokens mint with "bfk" (TokenService.create
 * reads TokenTypePrefix.workspace.prefix). "bfd" was considered (it briefly floated for a
 * dispatcher-tier token per an earlier plan) but T6-1 overruled that idea - dispatcher identity is
 * `bfg` + TokenActorKind, not a new prefix - so "bfd" carries no history here; "bfk" was chosen
 * anyway to keep "d" free for that possible future use.
 *
 * DEPRECATION WINDOW: only the SHA-256 hash of the full raw token (prefix included) is stored -
 * see TokenService#hashString/validate - so an already-issued "bft_..." token can never be
 * rewritten in the database; there is nothing to migrate. Both prefixes are therefore accepted
 * FOR THE LIFE OF THIS DEPRECATION WINDOW (no fixed end date is set - "bft" retires only when
 * every token minted under it has naturally expired, at whatever release removes the alias):
 * TOKEN_PATTERN (the pre-DB shape gate DispatcherAuthFilter/AuthenticationFilter rely on) matches
 * both "t" and "k", and BY_PREFIX resolves the legacy "bft" prefix to this same `workspace`
 * constant alongside its own "bfk". Hash-based validation itself needed no change at all - it
 * never inspected the prefix to begin with.
 */
public enum TokenTypePrefix {
  global("bfg"),
  workspace("bfk"),
  workflow("bfw"),
  user("bfu"),
  session("bfs");

  /** Deprecated prefix still accepted on presentation - see the class javadoc's window note. */
  private static final String LEGACY_WORKSPACE_PREFIX = "bft";

  public final String prefix;

  private static final Map<String, TokenTypePrefix> BY_PREFIX = new HashMap<>();

  // ARCHIE pattern (T6-1): a cheap, pre-DB shape gate — a bearer that doesn't even look like a
  // Flow token never reaches Mongo. Covers every prefix above (g/k/w/u/s) plus the deprecated
  // legacy workspace prefix "t" (H14-e's dual-acceptance window).
  private static final Pattern TOKEN_PATTERN = Pattern.compile("^bf[gktwus]_.+");

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
    // H14-e deprecation alias: tokens already issued with the pre-rename "bft" prefix keep
    // resolving to `workspace` - they can never be rewritten (only their hash is stored).
    BY_PREFIX.put(LEGACY_WORKSPACE_PREFIX, workspace);
  }

  public static TokenTypePrefix valueOfPrefix(String prefix) {
    return BY_PREFIX.get(prefix);
  }

  /** True if {@code token} is shaped like a Flow-minted token (any {@code bf?_...} prefix). */
  public static boolean isFlowToken(String token) {
    return token != null && TOKEN_PATTERN.matcher(token).matches();
  }
}
