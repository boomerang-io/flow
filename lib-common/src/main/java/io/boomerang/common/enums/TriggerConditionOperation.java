package io.boomerang.common.enums;

import java.util.Arrays;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TriggerConditionOperation {
  equals("equals"),
  matches("matches"),
  in("in");

  private String value;

  TriggerConditionOperation(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  public static TriggerConditionOperation getEnum(String flowTriggerEnum) {
    return Arrays.asList(TriggerConditionOperation.values()).stream()
        .filter(value -> value.getValue().equals(flowTriggerEnum))
        .findFirst()
        .orElse(null);
  }
}
