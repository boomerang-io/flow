package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** A resolved parameter on a run: the engine has already substituted every reference. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunParam {

  private String name;
  private Object value;

  public RunParam() {}

  public RunParam(String name, Object value) {
    this.name = name;
    this.value = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Object getValue() {
    return value;
  }

  public void setValue(Object value) {
    this.value = value;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof RunParam param)
        && Objects.equals(name, param.name)
        && Objects.equals(value, param.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, value);
  }

  // The value is left out: a param may carry a password.
  @Override
  public String toString() {
    return "RunParam[name=" + name + "]";
  }
}
