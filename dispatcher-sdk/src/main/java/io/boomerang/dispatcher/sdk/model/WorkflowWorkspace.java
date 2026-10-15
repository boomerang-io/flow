package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The one shared filesystem a run's tasks read and write, keyed by the run and released when the
 * run finishes. Any field left blank takes the dispatcher's default; a blank {@code mountPath} is
 * {@code /workspace}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowWorkspace {

  private String size;
  private String mountPath;
  private String className;
  private String accessMode;

  public String getSize() {
    return size;
  }

  public void setSize(String size) {
    this.size = size;
  }

  public String getMountPath() {
    return mountPath;
  }

  public void setMountPath(String mountPath) {
    this.mountPath = mountPath;
  }

  public String getClassName() {
    return className;
  }

  public void setClassName(String className) {
    this.className = className;
  }

  public String getAccessMode() {
    return accessMode;
  }

  public void setAccessMode(String accessMode) {
    this.accessMode = accessMode;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof WorkflowWorkspace workspace)
        && Objects.equals(size, workspace.size)
        && Objects.equals(mountPath, workspace.mountPath)
        && Objects.equals(className, workspace.className)
        && Objects.equals(accessMode, workspace.accessMode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(size, mountPath, className, accessMode);
  }

  @Override
  public String toString() {
    return "WorkflowWorkspace[size="
        + size
        + ", mountPath="
        + mountPath
        + ", className="
        + className
        + ", accessMode="
        + accessMode
        + "]";
  }
}
