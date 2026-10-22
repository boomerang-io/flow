package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum TriggerEnum {
  manual("manual"),
  schedule("schedule"),
  webhook("webhook"),
  event("event"),
  github("github"),
  engine("engine"),
  task("task");

  private String trigger;

  TriggerEnum(String trigger) {
    this.trigger = trigger;
  }

  @JsonValue
  public String getTrigger() {
    return trigger;
  }

  public static TriggerEnum getFlowTriggerEnum(String flowTriggerEnum) {
    return Arrays.asList(TriggerEnum.values()).stream()
        .filter(value -> value.getTrigger().equals(flowTriggerEnum))
        .findFirst()
        .orElse(null);
  }
}
