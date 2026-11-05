package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum TriggerEnum {
  manual("manual"),
  // A submit on demand by a token or a program rather than a person signed in to the webapp.
  api("api"),
  schedule("schedule"),
  webhook("webhook"),
  event("event"),
  github("github"),
  // Written by submits before the caller decided the trigger; read on runs stored then.
  engine("engine"),
  task("task"),
  retry("retry");

  private String trigger;

  TriggerEnum(String trigger) {
    this.trigger = trigger;
  }

  @JsonValue
  public String getTrigger() {
    return trigger;
  }

  // Read ignoring case, so a body may send "Manual" or "manual".
  public static TriggerEnum getFlowTriggerEnum(String flowTriggerEnum) {
    return Arrays.asList(TriggerEnum.values()).stream()
        .filter(value -> value.getTrigger().equalsIgnoreCase(flowTriggerEnum))
        .findFirst()
        .orElse(null);
  }

  @JsonCreator
  public static TriggerEnum fromJson(String trigger) {
    TriggerEnum value = getFlowTriggerEnum(trigger);
    if (value == null) {
      throw new IllegalArgumentException("Unknown trigger: " + trigger);
    }
    return value;
  }
}
