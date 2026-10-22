package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** A param's type; absent on the wire means {@link #string}. */
public enum ParamType {
  string,
  array,
  object,
  /** A string whose value is never shown again once sent. */
  secret;

  /** Read a type from the wire; a value this SDK does not know is null, which reads as string. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static ParamType from(String value) {
    for (ParamType type : values()) {
      if (type.name().equals(value)) {
        return type;
      }
    }
    return null;
  }
}
