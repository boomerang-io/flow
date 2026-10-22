package io.boomerang.engine.enums;

public enum EventType {

  // @formatter:off
  TRIGGER("io.boomerang.event.workflow.trigger"),
  WFE("io.boomerang.event.workflow.wfe"),
  CANCEL("io.boomerang.event.workflow.cancel"),
  WORKFLOW_STATUS_UPDATE("io.boomerang.event.status.workflow"),
  WORKFLOWRUN_STATUS_UPDATE("io.boomerang.event.status.workflowrun"),
  TASKRUN_STATUS_UPDATE("io.boomerang.event.status.taskrun");
  // @formatter:on

  private final String cloudEventType;

  private EventType(String cloudEventType) {
    this.cloudEventType = cloudEventType;
  }

  public String getCloudEventType() {
    return cloudEventType;
  }

  public static EventType valueOfCloudEventType(String extendedType) {
    for (EventType eventType : values()) {
      if (extendedType.startsWith(eventType.cloudEventType)) {
        return eventType;
      }
    }
    return null;
  }
}
