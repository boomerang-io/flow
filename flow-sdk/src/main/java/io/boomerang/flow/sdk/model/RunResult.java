package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A result a task reported, or a run's result mapped from one of its tasks. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResult(String name, String description, Object value) {

  public static RunResult of(String name, Object value) {
    return new RunResult(name, null, value);
  }
}
