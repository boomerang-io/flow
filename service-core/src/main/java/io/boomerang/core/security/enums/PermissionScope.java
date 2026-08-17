package io.boomerang.core.security.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Map;

/*
 * T6-3: a permission GRANT's scope (what a {@link io.boomerang.core.security.model.
 * ResolvedPermissions} entry is anchored to) - split out of the former overloaded {@link
 * AuthScope}, which conflated this with the token's own class. Named after ARCHIE's {@code
 * PermissionScope} (the maintainer's preferred name where the split has no churn reason to keep
 * the old name - see this track's ruling, specifications/merge-execution-plan.md T6-3).
 *
 * <p>Only two values exist because only two ever have: every {@code ResolvedPermissions} the
 * codebase builds is either anchored to a single workspace ({@code principal}=workspaceId) or
 * unscoped/platform-wide ({@code principal}="**"). A {@code key} token's grants must always be
 * {@code workspace} - never {@code global} (enforced in {@code TokenService}, see the {@code
 * AuthScope.key} javadoc).
 */
public enum PermissionScope {
  global("global"),
  workspace("workspace");

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
