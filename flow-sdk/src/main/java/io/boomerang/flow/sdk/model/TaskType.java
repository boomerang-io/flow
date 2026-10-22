package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** The kind of a task: what runs it and how the engine treats it. */
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
  /** A type added to the server after this SDK was built. */
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
