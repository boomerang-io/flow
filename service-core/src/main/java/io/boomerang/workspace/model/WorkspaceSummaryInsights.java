package io.boomerang.workspace.model;

public class WorkspaceSummaryInsights {

  private Long workflows;
  private Long members;
//  private long actions;
  public Long getWorkflows() {
    return workflows;
  }
  public void setWorkflows(Long workflows) {
    this.workflows = workflows;
  }
  public Long getMembers() {
    return members;
  }
  public void setMembers(Long members) {
    this.members = members;
  }
}
