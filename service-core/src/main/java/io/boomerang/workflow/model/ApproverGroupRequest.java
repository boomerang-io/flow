package io.boomerang.workflow.model;

import java.util.List;

public class ApproverGroupRequest {

  private String id;
  
  private String name;
  
  private List<String> approvers;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public List<String> getApprovers() {
    return approvers;
  }

  public void setApprovers(List<String> approvers) {
    this.approvers = approvers;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

}
