package io.boomerang.workspace.model;

import java.util.Date;
import org.springframework.beans.BeanUtils;

public class CurrentQuotas extends Quotas {
  
  private Integer currentWorkflowCount;
  private Integer currentRuns;
  private Integer currentConcurrentRuns;
  private Long currentRunTotalDuration;
  private Long currentRunAverageDuration;
  // Bytes held by the workspace's available and uploading artifacts.
  private Long currentArtifactStorage;
  // TODO: future - can't currently calculate this easily
//  private Integer currentTotalWorkflowStorage;
  private Date monthlyResetDate;
  
  public CurrentQuotas() {
  }
  public CurrentQuotas(Quotas quotas) {
    BeanUtils.copyProperties(quotas, this);
  }

  @Override
  public String toString() {
    return "CurrentQuotas [currentWorkflowCount=" + currentWorkflowCount + ", currentRuns="
        + currentRuns + ", currentConcurrentRuns=" + currentConcurrentRuns
        + ", currentRunTotalDuration=" + currentRunTotalDuration + ", currentRunAverageDuration="
        + currentRunAverageDuration + ", currentArtifactStorage=" + currentArtifactStorage
        + ", monthlyResetDate=" + monthlyResetDate + "]";
  }
  
  public Integer getCurrentWorkflowCount() {
    return currentWorkflowCount;
  }
  public void setCurrentWorkflowCount(Integer currentWorkflowCount) {
    this.currentWorkflowCount = currentWorkflowCount;
  }
  public Integer getCurrentRuns() {
    return currentRuns;
  }
  public void setCurrentRuns(Integer currentRuns) {
    this.currentRuns = currentRuns;
  }
  public Integer getCurrentConcurrentRuns() {
    return currentConcurrentRuns;
  }
  public void setCurrentConcurrentRuns(Integer currentConcurrentRuns) {
    this.currentConcurrentRuns = currentConcurrentRuns;
  }
  public Long getCurrentRunTotalDuration() {
    return currentRunTotalDuration;
  }
  public void setCurrentRunTotalDuration(Long currentRunDuration) {
    this.currentRunTotalDuration = currentRunDuration;
  }
  public Date getMonthlyResetDate() {
    return monthlyResetDate;
  }
  public void setMonthlyResetDate(Date monthlyResetDate) {
    this.monthlyResetDate = monthlyResetDate;
  }
  public Long getCurrentRunAverageDuration() {
    return currentRunAverageDuration;
  }
  public void setCurrentRunAverageDuration(Long currentRunAverageDuration) {
    this.currentRunAverageDuration = currentRunAverageDuration;
  }
  public Long getCurrentArtifactStorage() {
    return currentArtifactStorage;
  }
  public void setCurrentArtifactStorage(Long currentArtifactStorage) {
    this.currentArtifactStorage = currentArtifactStorage;
  }
}
