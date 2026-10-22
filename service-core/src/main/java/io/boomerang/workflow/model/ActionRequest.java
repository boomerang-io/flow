package io.boomerang.workflow.model;

public class ActionRequest {
  private String id;
private String comments;
//TODO change name of approved
private boolean approved;
  public String getId() {
    return id;
  }
  public void setId(String id) {
    this.id = id;
  }
  public String getComments() {
    return comments;
  }
  public void setComments(String comments) {
    this.comments = comments;
  }
  public boolean isApproved() {
    return approved;
  }
  public void setApproved(boolean approved) {
    this.approved = approved;
  }

}
