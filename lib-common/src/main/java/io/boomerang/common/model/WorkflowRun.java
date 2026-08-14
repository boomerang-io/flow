package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
@JsonPropertyOrder({
  "id",
  "creationDate",
  "status",
  "phase",
  "startTime",
  "duration",
  "statusMessage",
  "error",
  "timeout",
  "retries",
  "workflowRef",
  "workflowRevisionRef",
  "labels",
  "annotations",
  "params",
  "tasks"
})
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRun {

  @Id private String id;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Date creationDate;
  private Date startTime;
  private long duration = 0;
  private Long timeout;
  private Long retries;
  private Boolean debug;
  private RunStatus status = RunStatus.notstarted;
  private RunPhase phase = RunPhase.pending;
  private String statusMessage;
  private boolean isAwaitingApproval;
  private String workflowRef;
  private String workflowName;
  private String workflowDisplayName;
  private Integer workflowVersion;
  private String workflowRevisionRef;
  private String trigger;
  private String initiatedByRef;
  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private List<WorkflowWorkspace> workspaces = new LinkedList<>();
  private List<TaskRun> tasks;

  @Override
  public String toString() {
    return "WorkflowRun [id="
        + id
        + ", creationDate="
        + creationDate
        + ", startTime="
        + startTime
        + ", duration="
        + duration
        + ", timeout="
        + timeout
        + ", retries="
        + retries
        + ", debug="
        + debug
        + ", status="
        + status
        + ", phase="
        + phase
        + ", statusMessage="
        + statusMessage
        + ", isAwaitingApproval="
        + isAwaitingApproval
        + ", workflowRef="
        + workflowRef
        + ", workflowName="
        + workflowName
        + ", workflowVersion="
        + workflowVersion
        + ", workflowRevisionRef="
        + workflowRevisionRef
        + ", trigger="
        + trigger
        + ", params="
        + params
        + ", results="
        + results
        + ", workspaces="
        + workspaces
        + "]";
  }

  public boolean isAwaitingApproval() {
    return isAwaitingApproval;
  }

  public void setAwaitingApproval(boolean isAwaitingApproval) {
    this.isAwaitingApproval = isAwaitingApproval;
  }
}
