package io.boomerang.common.model;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class WorkflowRunRequest {

  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private List<RunParam> params = new LinkedList<>();
  private List<WorkflowWorkspace> workspaces = new LinkedList<>();
  private Long timeout;
  private Long retries;
  private Boolean debug;

  @Override
  public String toString() {
    return "WorkflowRunRequest [labels="
        + labels
        + ", annotations="
        + annotations
        + ", params="
        + params
        + ", workspaces="
        + workspaces
        + ", timeout="
        + timeout
        + ", retries="
        + retries
        + ", debug="
        + debug
        + "]";
  }
}
