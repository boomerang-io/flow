package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
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

  // Claim ownership for the workflow-level claimables (provision and teardown). claim.by
  // absent = unclaimed and eligible; written only by the claim Compare-And-Set. claim.seq
  // increments on every claim and is never cleared.
  @JsonIgnore private RunClaim claim;

  // Denormalised absolute deadline written at the start Compare-And-Set; absent = unguarded.
  // The watcher reaps on an indexed range scan - there are no in-memory timers.
  @JsonIgnore
  @Indexed(sparse = true)
  private Date timeoutAt;

  // Pause flag - never a status. Absent = not paused. Claiming, admission and the recovery
  // sweeps all exclude paused runs; resume clears the flag and reconciles.
  @Indexed(sparse = true)
  private Date pauseRequestedAt;

  private String trigger;
  private String initiatedByRef;

  // Auto-retry attempt count (absent/null = 0). initiatedByRef + trigger=retry carry the retry
  // lineage - all typed, never boomerang.io/* annotations.
  private Long retryCount;

  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private List<WorkflowWorkspace> workspaces = new LinkedList<>();

  /** Mongo field paths used in queries — keep in sync with the field names above. */
  public static final class Fields {
    private Fields() {}

    public static final String STATUS = "status";
    public static final String PHASE = "phase";
    public static final String CREATION_DATE = "creationDate";
    public static final String PAUSE_REQUESTED_AT = "pauseRequestedAt";
    public static final String CLAIM_BY = "claim.by";
    public static final String CLAIM_AT = "claim.at";
    public static final String CLAIM_LEASE_EXPIRES_AT = "claim.leaseExpiresAt";
    public static final String TIMEOUT_AT = "timeoutAt";
    public static final String WORKFLOW_REF = "workflowRef";
    public static final String WORKSPACES_0 = "workspaces.0";
    public static final String TRIGGER = "trigger";
    public static final String START_TIME = "startTime";
    public static final String CLAIM_SEQ = "claim.seq";
    public static final String AGENT_REF = "agentRef";
    public static final String DURATION = "duration";
    public static final String PARAMS = "params";
    public static final String STATUS_MESSAGE = "statusMessage";
  }
}
