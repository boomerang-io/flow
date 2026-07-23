package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/*
 * Typed failure classification driving retry policy. Classification is always typed - never
 * derived by matching on message strings.
 */
public enum RetryClass {
  generic("generic"),
  ratelimit("ratelimit"),
  terminal("terminal");

  private String label;

  RetryClass(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  public static RetryClass getRetryClass(String label) {
    return Arrays.asList(RetryClass.values()).stream()
        .filter(value -> value.getLabel().equals(label))
        .findFirst()
        .orElse(null);
  }
}
