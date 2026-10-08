package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** A run's status as the engine reports it; an end report sends succeeded, failed or invalid. */
public enum RunStatus {
  notstarted,
  ready,
  running,
  waiting,
  succeeded,
  failed,
  invalid,
  skipped,
  cancelled,
  timedout,
  /** A status added to the engine after this SDK was built. */
  unknown;

  /** Read a status from the wire; a value this SDK does not know is {@link #unknown}. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static RunStatus from(String value) {
    for (RunStatus status : values()) {
      if (status.name().equals(value)) {
        return status;
      }
    }
    return unknown;
  }
}
