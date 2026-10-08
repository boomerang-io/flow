package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * A task run's type. The engine hands a dispatcher only the types it registered; the rest run
 * inside the engine.
 */
public enum TaskType {
  start,
  end,
  template,
  custom,
  generic,
  decision,
  approval,
  setwfproperty,
  manual,
  eventwait,
  acquirelock,
  releaselock,
  runworkflow,
  runscheduledworkflow,
  script,
  ai,
  setwfstatus,
  sleep,
  uploadartifact,
  downloadartifact,
  /** A type added to the engine after this SDK was built. */
  unknown;

  /** Read a type from the wire; a value this SDK does not know is {@link #unknown}. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TaskType from(String value) {
    for (TaskType type : values()) {
      if (type.name().equals(value)) {
        return type;
      }
    }
    return unknown;
  }
}
