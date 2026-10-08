package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * A task result. On a task run it names a result the task declares; on an end report it carries
 * the value the task produced.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunResult {

  private String name;
  private String description;
  private Object value;

  public RunResult() {}

  public RunResult(String name, Object value) {
    this.name = name;
    this.value = value;
  }

  public RunResult(String name, String description, Object value) {
    this.name = name;
    this.description = description;
    this.value = value;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Object getValue() {
    return value;
  }

  public void setValue(Object value) {
    this.value = value;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof RunResult result)
        && Objects.equals(name, result.name)
        && Objects.equals(description, result.description)
        && Objects.equals(value, result.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, description, value);
  }

  @Override
  public String toString() {
    return "RunResult[name=" + name + "]";
  }
}
