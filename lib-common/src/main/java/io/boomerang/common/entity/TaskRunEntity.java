package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.common.model.WorkflowTaskDependency;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('task_runs')}")
@CompoundIndexes({
  @CompoundIndex(name = "status_phase_type_idx", def = "{'status': 1, 'phase': 1, 'type': 1}")
})
public class TaskRunEntity {

  @Id private String id;
  private TaskType type;
  private String name;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Date creationDate;
  private Date startTime;
  private long duration;
  private Long timeout;
  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private List<TaskWorkspace> workspaces = new LinkedList<>();
  private TaskRunSpec spec = new TaskRunSpec();
  @Indexed private RunStatus status;
  @Indexed private RunPhase phase;
  private String statusMessage;
  @JsonIgnore private boolean preApproved;
  @JsonIgnore private String decisionValue;
  @JsonIgnore private List<WorkflowTaskDependency> dependencies;
  private String taskRef;
  private Integer taskVersion;
  private String workflowRef;
  private String workflowRevisionRef;
  @Indexed private String workflowRunRef; // Indexed when retrieving task runs for a workflow run

  // Claim ownership. claim.by is the registered dispatcher id holding the claim; absent =
  // unclaimed and eligible; written only by the claim Compare-And-Set. claim.seq increments on every claim and is never cleared, fencing
  // out dispatches that carry a superseded claim.
  @JsonIgnore private RunClaim claim;

  // Denormalised absolute deadline (budget + grace) written at claim/start; absent = unguarded.
  // The watcher reaps on an indexed range scan - there are no in-memory timers.
  @JsonIgnore
  @Indexed(sparse = true)
  private Date timeoutAt;

  // Retry state. Absent = fully eligible; retry.after gates claim eligibility until the backoff
  // elapses. Written only by the fenced requeue, which never clears claim.seq.
  @JsonIgnore private RunRetry retry;

  // Wake time for a durable wait (sleep, or an acquirelock backoff). A waiting row is re-driven by
  // the watcher when waitUntil elapses - never a held thread. Absent = not a timed wait.
  @JsonIgnore
  @Indexed(sparse = true)
  private Date waitUntil;
}
