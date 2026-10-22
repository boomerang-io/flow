package io.boomerang.workflow.model;

public class Quotas {

  private Integer maxWorkflowCount;
  private Integer maxWorkflowRunMonthly;
  private Integer maxWorkflowStorage;
  private Integer maxWorkflowRunStorage;
  private Integer maxWorkflowRunDuration;
  private Integer maxConcurrentRuns;
  
  @Override
  public String toString() {
    return "Quotas [maxWorkflowCount=" + maxWorkflowCount + ", maxWorkflowRunMonthly="
        + maxWorkflowRunMonthly + ", maxWorkflowStorage=" + maxWorkflowStorage
        + ", maxWorkflowRunStorage=" + maxWorkflowRunStorage + ", maxWorkflowRunDuration="
        + maxWorkflowRunDuration + ", maxConcurrentRuns=" + maxConcurrentRuns + "]";
  }
  public Integer getMaxWorkflowCount() {
    return maxWorkflowCount;
  }
  public void setMaxWorkflowCount(Integer maxWorkflowCount) {
    this.maxWorkflowCount = maxWorkflowCount;
  }
  public Integer getMaxWorkflowRunMonthly() {
    return maxWorkflowRunMonthly;
  }
  public void setMaxWorkflowRunMonthly(Integer maxWorkflowRunMonthly) {
    this.maxWorkflowRunMonthly = maxWorkflowRunMonthly;
  }
  public Integer getMaxWorkflowStorage() {
    return maxWorkflowStorage;
  }
  public void setMaxWorkflowStorage(Integer maxWorkflowStorage) {
    this.maxWorkflowStorage = maxWorkflowStorage;
  }
  public Integer getMaxWorkflowRunStorage() {
    return maxWorkflowRunStorage;
  }
  public void setMaxWorkflowRunStorage(Integer maxWorkflowRunStorage) {
    this.maxWorkflowRunStorage = maxWorkflowRunStorage;
  }
  public Integer getMaxWorkflowRunDuration() {
    return maxWorkflowRunDuration;
  }
  public void setMaxWorkflowRunDuration(Integer maxWorkflowRunDuration) {
    this.maxWorkflowRunDuration = maxWorkflowRunDuration;
  }
  public Integer getMaxConcurrentRuns() {
    return maxConcurrentRuns;
  }
  public void setMaxConcurrentRuns(Integer maxConcurrentRuns) {
    this.maxConcurrentRuns = maxConcurrentRuns;
  }
}
