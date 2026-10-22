package io.boomerang.common.model;

import io.boomerang.common.enums.RunStatus;
import java.util.Date;
import lombok.Data;

@Data
public class WorkflowRunSummary {

  private Date creationDate;
  private long duration = 0L;
  private RunStatus status = RunStatus.notstarted;
  private String workflowRef;
  private String workflowName;

  @Override
  public String toString() {
    return "WorkflowRunSummary [creationDate="
        + creationDate
        + ", duration="
        + duration
        + ", status="
        + status
        + ", workflowRef="
        + workflowRef
        + "]";
  }
}
