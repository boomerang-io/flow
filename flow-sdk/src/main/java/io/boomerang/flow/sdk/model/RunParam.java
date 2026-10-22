package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A parameter on a run or a request. {@code type} absent means string; a {@code secret} sent on a
 * request is never shown again, and one read from the server is the redaction marker.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunParam(String name, Object value, ParamType type) {

  public static RunParam of(String name, Object value) {
    return new RunParam(name, value, null);
  }

  public static RunParam secret(String name, String value) {
    return new RunParam(name, value, ParamType.secret);
  }

  // The value is left out: a param may carry a secret.
  @Override
  public String toString() {
    return "RunParam[name=" + name + ", type=" + type + "]";
  }
}
