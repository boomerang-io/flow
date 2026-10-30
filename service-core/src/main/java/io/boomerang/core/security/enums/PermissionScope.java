package io.boomerang.core.security.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

/*
 * A permission GRANT's scope (what a {@link io.boomerang.core.security.model.
 * ResolvedPermissions} entry is anchored to) - split out of the former overloaded {@link
 * AuthScope}, which conflated this with the token's own class (see
 * specifications/authorization.md).
 *
 * <p>Three values: a grant is anchored to a single workspace ({@code principal}=workspaceId),
 * unscoped/platform-wide ({@code principal}="**"), or to the signed-in user themselves
 * ({@code principal}=userId): the self-service baseline every human session carries, which no
 * check that keys on {@code global} ever counts. A {@code key} token's grants must always be
 * {@code workspace} - never {@code global} (enforced in {@code TokenService}, see the {@code
 * AuthScope.key} javadoc).
 */
public enum PermissionScope {
  global("global"),
  workspace("workspace"),
  user("user");

  private String label;

  private static final Map<String, PermissionScope> BY_LABEL = new HashMap<>();

  PermissionScope(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  static {
    for (PermissionScope e : values()) {
      BY_LABEL.put(e.label, e);
    }
  }

  @JsonCreator
  public static PermissionScope valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
}
