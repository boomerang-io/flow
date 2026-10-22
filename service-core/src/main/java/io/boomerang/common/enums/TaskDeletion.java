package io.boomerang.common.enums;

import java.util.Arrays;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskDeletion {
  Never("Never"),
  OnSuccess("OnSuccess"),
  Always("Always");

  private String deletion;

  TaskDeletion(String deletion) {
    this.deletion = deletion;
  }

  @JsonValue
  public String getDeletion() {
    return deletion;
  }

  public static TaskDeletion getDeletion(String deletion) {
    return Arrays.asList(TaskDeletion.values()).stream()
        .filter(value -> value.getDeletion().equals(deletion))
        .findFirst()
        .orElse(null);
  }
}
