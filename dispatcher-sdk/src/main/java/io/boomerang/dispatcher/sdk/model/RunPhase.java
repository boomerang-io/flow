package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Where a run is in the engine's lifecycle. A dispatcher reads it, with {@link RunStatus}, to tell
 * an order to run from an order to terminate.
 */
public enum RunPhase {
  queued,
  pending,
  running,
  completed,
  /** A phase added to the engine after this SDK was built. */
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
