package io.boomerang.workspace.model;

public class WorkspaceNameCheckRequest {

  private String name;

  public WorkspaceNameCheckRequest() {
    // Empty
  }

  public WorkspaceNameCheckRequest(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
