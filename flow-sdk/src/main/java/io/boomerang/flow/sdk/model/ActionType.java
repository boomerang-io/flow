package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** What an action waits for: an approval by the named approvers, or a manual task's completion. */
public enum ActionType {
  approval,
  manual,
  /** A type added to the server after this SDK was built. */
  unknown;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static ActionType from(String value) {
    for (ActionType type : values()) {
      if (type.name().equals(value)) {
        return type;
      }
    }
    return unknown;
  }
}
