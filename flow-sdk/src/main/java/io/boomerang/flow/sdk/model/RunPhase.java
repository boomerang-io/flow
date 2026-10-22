package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Where a run is in its lifecycle, beside its status; {@code completed} is terminal. */
public enum RunPhase {
  queued,
  pending,
  running,
  completed,
  /** A phase added to the server after this SDK was built. */
  unknown;

  /** Read a phase from the wire; a value this SDK does not know is {@link #unknown}. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static RunPhase from(String value) {
    for (RunPhase phase : values()) {
      if (phase.name().equals(value)) {
        return phase;
      }
    }
    return unknown;
  }
}
