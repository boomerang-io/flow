package io.boomerang.audit;

import java.util.HashMap;
import java.util.Map;

public enum AuditType {
  created("created"), updated("updated"), deleted("deleted"), submitted("submitted"), cancelled("cancelled"), // NOSONAR
  notstarted("notstarted"), ready("ready"), running("running"), waiting("waiting"),  // NOSONAR
  succeeded("succeeded"), failed("failed"), invalid("invalid"), skipped("skipped"), timedout("timedout"); // NOSONAR

  private String label;

  private static final Map<String, AuditType> BY_LABEL = new HashMap<>();
  
  AuditType(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
  
  static {
      for (AuditType e: values()) {
        BY_LABEL.put(e.label, e);
      }
  }

  public static AuditType valueOfLabel(String label) {
    return BY_LABEL.get(label);
  }
  
}
