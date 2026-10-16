package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/** What a retried run re-ran: the whole graph, the tasks that did not succeed, or named tasks. */
public enum RetryScope {
  all,
  failed,
  nodes,
  /** A scope added to the engine after this SDK was built. */
  unknown;

  /** Read a scope from the wire; a value this SDK does not know is {@link #unknown}. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static RetryScope from(String value) {
    for (RetryScope scope : values()) {
      if (scope.name().equals(value)) {
        return scope;
      }
    }
    return unknown;
  }
}
