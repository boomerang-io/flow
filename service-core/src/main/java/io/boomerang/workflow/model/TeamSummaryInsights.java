package io.boomerang.workflow.model;

public class TeamSummaryInsights {

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
