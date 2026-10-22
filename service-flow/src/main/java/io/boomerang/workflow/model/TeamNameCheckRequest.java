package io.boomerang.workflow.model;

public class TeamNameCheckRequest {

  private String name;

  public TeamNameCheckRequest() {
    // Empty
  }

  public TeamNameCheckRequest(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
