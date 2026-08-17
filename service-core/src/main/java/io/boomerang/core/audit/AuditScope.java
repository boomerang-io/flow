package io.boomerang.core.audit;

import java.util.HashMap;
import java.util.Map;

/*
 * H14-c (DD-01 wire-name sweep): TEAM's constant name/label became WORKSPACE/"workspace".
 * Unlike AuthScope/RelationshipType/PermissionResource, AuditScope has no custom
 * @JsonCreator/valueOfLabel parse path at the Mongo boundary - Spring Data deserializes this
 * @Indexed field via the default enum-name converter (Enum.valueOf), which has NO alias
 * mechanism. A stray persisted "TEAM" would therefore throw on load, not just mis-resolve - so
 * loader changeunit _0016__WorkspaceRename's audit.scope "TEAM"->"WORKSPACE" rewrite is NOT
 * merely defensive here, it is required for correctness on any pre-existing audit document.
 */
public enum AuditScope {
  SYSTEM("system"), WORKFLOW("workflow"), WORKFLOWRUN("workflowrun"), WORKFLOWTEMPLATE("workflowtemplate"), TASKRUN("taskrun"), TASKTEMPLATE("tasktemplate)"),
  ACTION("action"), USER("user"), WORKSPACE("workspace"), TOKEN("token"), PARAMETER("parameter"), SCHEDULE("schedule"), INSIGHTS("insights"), INTEGRATION("integration"), ANY("**");

  private String label;

  private static final Map<String, AuditScope> BY_LABEL = new HashMap<>();

  AuditScope(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  static {
      for (AuditScope e: values()) {
        BY_LABEL.put(e.label, e);
      }
  }

  public static AuditScope valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }

}
