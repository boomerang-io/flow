package io.boomerang.core.audit;

import java.util.HashMap;
import java.util.Map;

public enum AuditScope {
  SYSTEM("system"), WORKFLOW("workflow"), WORKFLOWRUN("workflowrun"), WORKFLOWTEMPLATE("workflowtemplate"), TASKRUN("taskrun"), TASKTEMPLATE("tasktemplate)"), 
  ACTION("action"), USER("user"), TEAM("team"), TOKEN("token"), PARAMETER("parameter"), SCHEDULE("schedule"), INSIGHTS("insights"), INTEGRATION("integration"), ANY("**");

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
