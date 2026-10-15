package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** The runs a dispatcher holds a workspace volume for: at most 500 per query. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceReleaseQuery {

  private List<String> workflowRunRefs = new ArrayList<>();

  public List<String> getWorkflowRunRefs() {
    return workflowRunRefs;
  }

  public void setWorkflowRunRefs(List<String> workflowRunRefs) {
    this.workflowRunRefs = workflowRunRefs;
  }
}
