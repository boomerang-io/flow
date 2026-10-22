package io.boomerang.agent.model;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.boomerang.common.model.WorkflowWorkspace;

@JsonIgnoreProperties
public class WorkspaceRequest extends WorkflowWorkspace {

  private String workflowRunRef;

  private String workflowRef;

  @JsonProperty("labels")
  private Map<String, String> labels = new HashMap<>();

  @Override
  public String toString() {
    return "ControllerWorkspaceRequest [workflowRunRef="
        + workflowRunRef
        + ", workflowRef="
        + workflowRef
        + ", labels="
        + labels
        + ", toString()="
        + super.toString()
        + "]";
  }

  public String getWorkflowRef() {
    return workflowRef;
  }

  public void setWorkflowRef(String workflowRef) {
    this.workflowRef = workflowRef;
  }

  public String getWorkflowRunRef() {
    return workflowRunRef;
  }

  public void setWorkflowRunRef(String workflowRunRef) {
    this.workflowRunRef = workflowRunRef;
  }

  public Map<String, String> getLabels() {
    return labels;
  }

  public void setLabels(Map<String, String> labels) {
    this.labels = labels;
  }

  public void setLabel(String name, String value) {
    this.labels.put(name, value);
  }
}
