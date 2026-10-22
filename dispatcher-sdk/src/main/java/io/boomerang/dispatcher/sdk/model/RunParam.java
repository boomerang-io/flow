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
  // Absent means string. A secret's value is in plain text here: keep it out of logs.
  private ParamType type;

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

  public ParamType getType() {
    return type;
  }

  public void setType(ParamType type) {
    this.type = type;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof RunParam param)
        && Objects.equals(name, param.name)
        && Objects.equals(value, param.value)
        && type == param.type;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, value, type);
  }

  // The value is left out: a param may carry a password.
  @Override
  public String toString() {
    return "RunParam[name=" + name + ", type=" + type + "]";
  }
}
