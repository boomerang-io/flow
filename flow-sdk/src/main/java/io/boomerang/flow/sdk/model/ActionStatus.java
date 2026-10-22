package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** An action's status: created {@code submitted}, decided {@code approved} or {@code rejected}. */
public enum ActionStatus {
  submitted,
  approved,
  rejected,
  cancelled,
  /** A status added to the server after this SDK was built. */
  unknown;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static ActionStatus from(String value) {
    for (ActionStatus status : values()) {
      if (status.name().equals(value)) {
        return status;
      }
    }
    return unknown;
  }
}
