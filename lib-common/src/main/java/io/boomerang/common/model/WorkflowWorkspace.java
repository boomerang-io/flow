package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;

/**
 * The one shared filesystem a run's tasks can read and write. Declared once on the workflow,
 * copied onto the run when it is submitted, and onto each TaskRun that mounts it. Presence is the
 * switch: a workflow without one has no shared storage. The volume is keyed by the run and removed
 * when the run completes; files that must survive between runs are artifacts with scope
 * {@code workflow}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class WorkflowWorkspace {

  public static final String DEFAULT_MOUNT_PATH = "/workspace";

  /** A Kubernetes quantity ({@code 2Gi}, {@code 500Mi}); a bare number means Gi. */
  private String size;

  /** Where every task sees the workspace. Blank means {@link #DEFAULT_MOUNT_PATH}. */
  private String mountPath;

  /** Storage class; blank leaves the cluster default. */
  private String className;

  /** Access mode; blank takes the dispatcher's default. */
  private String accessMode;

  public String mountPathOrDefault() {
    return mountPath == null || mountPath.isBlank() ? DEFAULT_MOUNT_PATH : mountPath;
  }
}
