package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * The owners of the workspace volumes a dispatcher holds, by storage type: at most 500 of each per
 * query.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceReleaseQuery {

  private List<String> workflowRunRefs = new ArrayList<>();
  private List<String> workflowRefs = new ArrayList<>();

  public List<String> getWorkflowRunRefs() {
    return workflowRunRefs;
  }

  public void setWorkflowRunRefs(List<String> workflowRunRefs) {
    this.workflowRunRefs = workflowRunRefs;
  }

  public List<String> getWorkflowRefs() {
    return workflowRefs;
  }

  public void setWorkflowRefs(List<String> workflowRefs) {
    this.workflowRefs = workflowRefs;
  }
}
