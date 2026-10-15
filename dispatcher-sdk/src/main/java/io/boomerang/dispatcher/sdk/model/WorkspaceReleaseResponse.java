package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** The subset of a {@link WorkspaceReleaseQuery} whose run is finished, so its volume may go. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceReleaseResponse {

  private List<String> workflowRunRefs = new ArrayList<>();

  public List<String> getWorkflowRunRefs() {
    return workflowRunRefs;
  }

  public void setWorkflowRunRefs(List<String> workflowRunRefs) {
    this.workflowRunRefs = workflowRunRefs;
  }
}
