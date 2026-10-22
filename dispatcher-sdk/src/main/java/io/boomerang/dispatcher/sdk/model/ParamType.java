package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * A run parameter's type. Absent on the wire means string. A secret is a string whose value the
 * engine never shows a consumer; a dispatcher receives it in plain text and should keep it out of
 * its own logs.
 */
public enum ParamType {
  string,
  array,
  object,
  secret,
  /** A type added to the engine after this SDK was built. */
  unknown;

  /** Read a type from the wire; a value this SDK does not know is {@link #unknown}. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static ParamType from(String value) {
    for (ParamType type : values()) {
      if (type.name().equals(value)) {
        return type;
      }
    }
    return unknown;
  }
}
