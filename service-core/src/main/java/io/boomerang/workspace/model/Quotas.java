package io.boomerang.workspace.model;

/**
 * A workspace's quotas. A stored value has three states: absent inherits the platform default from
 * the {@code quotas} settings document, {@code 0} is unlimited, and a positive number is the limit.
 * The merge with the defaults resolves the absent state; {@link #limited} resolves the other two.
 */
public class Quotas {

  /** Whether a quota value is a limit to enforce. {@code 0} is unlimited. */
  public static boolean limited(Integer quota) {
    return quota != null && quota > 0;
  }

  private Integer maxWorkflowCount;
  private Integer maxWorkflowRunMonthly;
  // Gi of shared workspace a run may declare.
  private Integer maxWorkspaceStorage;
  private Integer maxWorkflowRunDuration;
  private Integer maxConcurrentRuns;
  // Gi of unexpired artifacts the workspace may hold, and the days an artifact is kept.
  private Integer maxArtifactStorage;
  private Integer artifactRetentionDays;

  @Override
  public String toString() {
    return "Quotas [maxWorkflowCount=" + maxWorkflowCount + ", maxWorkflowRunMonthly="
        + maxWorkflowRunMonthly + ", maxWorkspaceStorage=" + maxWorkspaceStorage
        + ", maxWorkflowRunDuration="
        + maxWorkflowRunDuration + ", maxConcurrentRuns=" + maxConcurrentRuns
        + ", maxArtifactStorage=" + maxArtifactStorage + ", artifactRetentionDays="
        + artifactRetentionDays + "]";
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
  public Integer getMaxWorkspaceStorage() {
    return maxWorkspaceStorage;
  }
  public void setMaxWorkspaceStorage(Integer maxWorkspaceStorage) {
    this.maxWorkspaceStorage = maxWorkspaceStorage;
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
  public Integer getMaxArtifactStorage() {
    return maxArtifactStorage;
  }
  public void setMaxArtifactStorage(Integer maxArtifactStorage) {
    this.maxArtifactStorage = maxArtifactStorage;
  }
  public Integer getArtifactRetentionDays() {
    return artifactRetentionDays;
  }
  public void setArtifactRetentionDays(Integer artifactRetentionDays) {
    this.artifactRetentionDays = artifactRetentionDays;
  }
}
