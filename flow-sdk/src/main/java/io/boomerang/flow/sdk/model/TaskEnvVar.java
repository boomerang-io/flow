package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One environment variable of a container task. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskEnvVar(String name, String value) {

  // The value is left out: it may be a secret.
  @Override
  public String toString() {
    return "TaskEnvVar[name=" + name + "]";
  }
}
