package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** When a dispatcher removes a finished task's runtime object. */
public enum TaskDeletion {
  Never,
  OnSuccess,
  Always;

  /** Read a policy from the wire; a value this SDK does not know is null. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TaskDeletion from(String value) {
    for (TaskDeletion deletion : values()) {
      if (deletion.name().equals(value)) {
        return deletion;
      }
    }
    return null;
  }
}
