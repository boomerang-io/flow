package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A workspace a task mounts: its type ({@code workflow} or {@code workflowrun}) and where. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskWorkspace {

  private String name;
  private String type;
  private boolean optional;
  private String mountPath;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public boolean isOptional() {
    return optional;
  }

  public void setOptional(boolean optional) {
    this.optional = optional;
  }

  public String getMountPath() {
    return mountPath;
  }

  public void setMountPath(String mountPath) {
    this.mountPath = mountPath;
  }

  @Override
  public String toString() {
    return "TaskWorkspace[name=" + name + ", type=" + type + ", mountPath=" + mountPath + "]";
  }
}
