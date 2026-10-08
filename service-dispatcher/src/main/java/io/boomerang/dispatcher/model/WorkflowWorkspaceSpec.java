package io.boomerang.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The storage a workflow workspace asks for; any field left blank takes the dispatcher default. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowWorkspaceSpec {

  private String accessMode;
  private String className;
  private String mountPath;
  private String size;

  public String getAccessMode() {
    return accessMode;
  }

  public void setAccessMode(String accessMode) {
    this.accessMode = accessMode;
  }

  public String getClassName() {
    return className;
  }

  public void setClassName(String className) {
    this.className = className;
  }

  public String getMountPath() {
    return mountPath;
  }

  public void setMountPath(String mountPath) {
    this.mountPath = mountPath;
  }

  public String getSize() {
    return size;
  }

  public void setSize(String size) {
    this.size = size;
  }
}
