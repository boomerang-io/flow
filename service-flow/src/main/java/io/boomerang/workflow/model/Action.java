package io.boomerang.workflow.model;

import org.springframework.beans.BeanUtils;
import io.boomerang.common.entity.ActionEntity;

public class Action extends ActionEntity {

  private String taskName;
  private String workflowName;
  private String teamName;
  private long numberOfApprovals;
  private long approvalsRequired;

  public Action() {}

  public Action(ActionEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }

  public String getTaskName() {
    return taskName;
  }

  public void setTaskName(String taskName) {
    this.taskName = taskName;
  }

  public String getWorkflowName() {
    return workflowName;
  }

  public void setWorkflowName(String workflowName) {
    this.workflowName = workflowName;
  }

  public String getTeamName() {
    return teamName;
  }

  public void setTeamName(String teamName) {
    this.teamName = teamName;
  }

  public long getApprovalsRequired() {
    return approvalsRequired;
  }

  public void setApprovalsRequired(long approvalsRequired) {
    this.approvalsRequired = approvalsRequired;
  }

  public long getNumberOfApprovals() {
    return numberOfApprovals;
  }

  public void setNumberOfApprovals(long numberOfApprovals) {
    this.numberOfApprovals = numberOfApprovals;
  }
}
