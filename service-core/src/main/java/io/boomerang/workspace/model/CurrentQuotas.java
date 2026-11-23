package io.boomerang.workspace.model;

import java.util.Date;
import org.springframework.beans.BeanUtils;

public class CurrentQuotas extends Quotas {
  
  private Integer currentWorkflowCount;
  private Integer currentRuns;
  // Runs holding a slot: admitted, running or in their finally stage.
  private Integer currentConcurrentRuns;
  // Runs waiting for a slot.
  private Integer currentQueuedRuns;
  private Long currentRunTotalDuration;
  private Long currentRunAverageDuration;
  // Minutes of task time the month's ended runs used, each run rounded up.
  private Long currentCumulativeDuration;
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
        + ", currentQueuedRuns=" + currentQueuedRuns
        + ", currentRunTotalDuration=" + currentRunTotalDuration + ", currentRunAverageDuration="
        + currentRunAverageDuration + ", currentCumulativeDuration=" + currentCumulativeDuration
        + ", currentArtifactStorage=" + currentArtifactStorage
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
  public Integer getCurrentQueuedRuns() {
    return currentQueuedRuns;
  }
  public void setCurrentQueuedRuns(Integer currentQueuedRuns) {
    this.currentQueuedRuns = currentQueuedRuns;
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
  public Long getCurrentCumulativeDuration() {
    return currentCumulativeDuration;
  }
  public void setCurrentCumulativeDuration(Long currentCumulativeDuration) {
    this.currentCumulativeDuration = currentCumulativeDuration;
  }
  public Long getCurrentArtifactStorage() {
    return currentArtifactStorage;
  }
  public void setCurrentArtifactStorage(Long currentArtifactStorage) {
    this.currentArtifactStorage = currentArtifactStorage;
  }
}
