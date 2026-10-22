package io.boomerang.common.model;

import lombok.Data;

@Data
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
}
