package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;

/**
 * A workflow run as the workflow queue hands it out, the contract's {@code DispatchWorkflowRun}:
 * one that declares a workspace, claimed so the dispatcher can provision its storage before its
 * tasks run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRun {

  private String id;
  private RunStatus status;
  private RunPhase phase;
  private String workflowRef;
  private Map<String, String> labels = new HashMap<>();
  private WorkflowWorkspace workspace;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
  }

  public RunPhase getPhase() {
    return phase;
  }

  public void setPhase(RunPhase phase) {
    this.phase = phase;
  }

  public String getWorkflowRef() {
    return workflowRef;
  }

  public void setWorkflowRef(String workflowRef) {
    this.workflowRef = workflowRef;
  }

  public Map<String, String> getLabels() {
    return labels;
  }

  public void setLabels(Map<String, String> labels) {
    this.labels = labels;
  }

  public WorkflowWorkspace getWorkspace() {
    return workspace;
  }

  public void setWorkspace(WorkflowWorkspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public String toString() {
    return "WorkflowRun[id="
        + id
        + ", workflowRef="
        + workflowRef
        + ", phase="
        + phase
        + ", status="
        + status
        + ", workspace="
        + workspace
        + "]";
  }
}
