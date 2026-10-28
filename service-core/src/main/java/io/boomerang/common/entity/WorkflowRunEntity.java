package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RetryFrom;
import io.boomerang.common.model.RunClaim;
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
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('workflow_runs')}")
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
  private RunStatus status = RunStatus.notstarted;
  private RunPhase phase = RunPhase.pending;
  private RunStatus statusOverride;
  private String statusMessage;
  // The typed cause the engine ends a run with, beside the human-readable statusMessage; absent
  // when the run ended for no cause of its own (succeeded, or cancelled on request).
  private String statusReason;
  private boolean isAwaitingApproval;
  private String workflowRef;
  private Integer workflowVersion;
  private String workflowRevisionRef;

  // Claim ownership for the workflow-level claimables (provision and teardown). claim.by is the
  // registered dispatcher id holding the claim; absent = unclaimed and eligible; written only by
  // the claim Compare-And-Set. claim.seq increments on every claim and is never cleared.
  @JsonIgnore private RunClaim claim;

  // Denormalised absolute deadline written at the start Compare-And-Set; absent = unguarded.
  // The watcher reaps on an indexed range scan - there are no in-memory timers.
  @JsonIgnore private Date timeoutAt;

  // Pause flag - never a status. Absent = not paused. Claiming, admission and the recovery
  // sweeps all exclude paused runs; resume clears the flag and reconciles.
  private Date pauseRequestedAt;

  // Derived from pauseRequestedAt - orthogonal to status/phase. A paused run keeps its
  // underlying status and phase; only in-flight admission is gated.
  public boolean isPaused() {
    return pauseRequestedAt != null;
  }

  // Set while every in-flight task is waiting (a gate, an event, a child run, a parked wait), so
  // the run clock is stopped: the timeout sweep skips the run, and when work resumes the deadline
  // moves forward by the time spent here. Absent = executing. Never on the public model.
  @JsonIgnore private Date waitingSince;

  private String trigger;
  private String initiatedByRef;

  // Auto-retry attempt count (absent/null = 0). initiatedByRef + trigger=retry carry the retry
  // lineage - all typed, never boomerang.io/* annotations.
  @JsonIgnore private Long retryCount;

  // Where a scoped retry started: the scope and the task names it re-ran from. Absent on a plain
  // retry and on every other run. Typed, so the engine and the webapp both read it.
  private RetryFrom retryFrom;

  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private WorkflowWorkspace workspace;
}
