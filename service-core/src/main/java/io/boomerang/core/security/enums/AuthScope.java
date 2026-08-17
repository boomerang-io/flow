package io.boomerang.core.security.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

/*
 * T6-3: the TOKEN CLASS (actor/ceiling-typed, not resource-scope-typed) - who/what a token
 * represents and the ceiling of what it can ever be granted. This is deliberately NOT the same
 * concept as a permission grant's scope (see {@link PermissionScope}) - see this track's ruling
 * (specifications/merge-execution-plan.md, T6-3) for why the two were split out of what used to be
 * a single overloaded enum.
 *
 * <ul>
 *   <li>{@code session} - human, short-lived, permissions re-resolved on login/exchange.
 *   <li>{@code user} - human PAT, long-lived, minted by the user, ceiling = that user's access.
 *   <li>{@code key} - machine (service/agent/workflow), workspace-bound. MUST NEVER hold a
 *       {@code global}-scoped grant (enforced in {@code TokenService}, not just documented).
 *       Replaces the retired {@code workspace} class 1:1 (same prefix, {@code bfk}) and absorbs
 *       the retired {@code workflow} class ({@code actorKind=WORKFLOW} + {@code
 *       principal=<workflowId>} replaces the old {@code bfw} workflow token).
 *   <li>{@code global} - platform/admin, ceiling = everything. Creation requires admin authority
 *       (enforced in {@code TokenService.create}).
 * </ul>
 *
 * Retired: {@code workspace} (renamed to {@code key}) and {@code workflow} (folded into {@code
 * key} + {@code actorKind=WORKFLOW}). Per the maintainer's no-deprecation-window ruling, their raw
 * token prefixes ({@code bft}/{@code bfw}) are also retired outright in {@link
 * io.boomerang.core.enums.TokenTypePrefix} - operators re-issue. Remains lowercase to match
 * {@code TokenTypePrefix} and what a user would enter in json.
 */
public enum AuthScope {
  session("session"),
  user("user"),
  key("key"),
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
  }

  @JsonCreator
  public static AuthScope valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
