package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A workspace a workflow run declares: shared storage of type {@code workflow} (kept across runs)
 * or {@code workflowrun} (one run's), with a free-form {@code spec} such as size, class, access
 * mode and mount path.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowWorkspace {

  private String name;
  private String description;
  private String type;
  private boolean optional;
  private Object spec;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
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

  public Object getSpec() {
    return spec;
  }

  public void setSpec(Object spec) {
    this.spec = spec;
  }

  @Override
  public String toString() {
    return "WorkflowWorkspace[name=" + name + ", type=" + type + "]";
  }
}
