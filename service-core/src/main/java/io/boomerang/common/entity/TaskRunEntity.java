package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunAttempt;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.WorkflowWorkspace;
import io.boomerang.common.model.WorkflowTaskForeach;
import io.boomerang.common.model.WorkflowTaskDependency;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('task_runs')}")
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
  private WorkflowWorkspace workspace;
  private TaskRunSpec spec = new TaskRunSpec();
  private RunStatus status;
  private RunPhase phase;
  private String statusMessage;

  private String statusReason;
  // The status an event delivered before its wait task was ready; the wait ends with it.
  @JsonIgnore private RunStatus preApprovedStatus;
  @JsonIgnore private String decisionValue;
  @JsonIgnore private List<WorkflowTaskDependency> dependencies;
  private String taskRef;
  private Integer taskVersion;
  private String workflowRef;
  private String workflowRevisionRef;
  private String workflowRunRef;

  // A foreach task's item: the parent TaskRun's id and the item's position from 0. Absent on every
  // other TaskRun. Items are never graph vertices; the parent stands for them in the graph.
  private String parentRef;
  private Integer index;

  // Set on a TaskRun a scoped retry carried over instead of executing: the origin TaskRun it
  // stands for. Its log is read through this id rather than duplicated. Absent on every TaskRun
  // that ran.
  private String reusedFromRef;

  // The foreach setting, on a parent only. Holds the resolved item array once the parent fans out.
  @JsonIgnore private WorkflowTaskForeach foreach;

  // Claim ownership. claim.by is the registered dispatcher id holding the claim; absent =
  // unclaimed and eligible; written only by the claim Compare-And-Set. claim.seq increments on every claim and is never cleared, fencing
  // out dispatches that carry a superseded claim.
  @JsonIgnore private RunClaim claim;

  // Denormalised absolute deadline (budget + grace) written at claim/start; absent = unguarded.
  // The watcher reaps on an indexed range scan - there are no in-memory timers.
  @JsonIgnore private Date timeoutAt;

  // Retry state. Absent = fully eligible; retry.after gates claim eligibility until the backoff
  // elapses. Written only by the fenced requeue, which never clears claim.seq.
  @JsonIgnore private RunRetry retry;

  // The attempts a requeue superseded, oldest first, each with how it ended and who held it. The
  // run's own status, reason, message and duration are the current attempt. Absent until the first
  // requeue, so every task run that never retried reads as its first attempt.
  private List<RunAttempt> attempts;

  // Wake time for a durable wait (sleep, or an acquirelock backoff). A waiting row is re-driven by
  // the watcher when waitUntil elapses - never a held thread. Absent = not a timed wait.
  @JsonIgnore private Date waitUntil;
}
