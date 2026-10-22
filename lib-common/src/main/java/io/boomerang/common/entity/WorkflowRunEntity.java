package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.WorkflowWorkspace;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('workflow_runs')}")
@CompoundIndexes({@CompoundIndex(name = "status_phase_idx", def = "{'status': 1, 'phase': 1}")})
public class WorkflowRunEntity {

  @Id private String id;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Date creationDate;
  private Date startTime;
  private long duration = 0;
  private Long timeout;
  private Long retries;
  private Boolean debug;
  @Indexed private RunStatus status = RunStatus.notstarted;
  @Indexed private RunPhase phase = RunPhase.pending;
  private RunStatus statusOverride;
  private String statusMessage;
  private boolean isAwaitingApproval;
  private String workflowRef;
  private Integer workflowVersion;
  private String workflowRevisionRef;
  private String agentRef;
  private String trigger;
  private String initiatedByRef;
  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private List<WorkflowWorkspace> workspaces = new LinkedList<>();

  @Override
  public String toString() {
    return "WorkflowRunEntity [id="
        + id
        + ", labels="
        + labels
        + ", annotations="
        + annotations
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
        + ", statusOverride="
        + statusOverride
        + ", statusMessage="
        + statusMessage
        + ", isAwaitingApproval="
        + isAwaitingApproval
        + ", workflowRef="
        + workflowRef
        + ", workflowRevisionRef="
        + workflowRevisionRef
        + ", trigger="
        + trigger
        + ", initiatedByRef="
        + initiatedByRef
        + ", params="
        + params
        + ", results="
        + results
        + ", workspaces="
        + workspaces
        + "]";
  }
}
