package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A task run as the engine hands it out. With {@code phase} {@code queued} and {@code status}
 * {@code ready} it is an order to run; with {@code phase} {@code completed} and {@code status}
 * {@code cancelled} or {@code timedout} it is an order to terminate.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRun {

  private String id;
  private TaskType type;
  private String name;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Instant creationDate;
  private Instant startTime;
  private Long duration;
  private Long timeout;
  private List<RunParam> params = new ArrayList<>();
  private List<RunResult> results = new ArrayList<>();
  private List<TaskWorkspace> workspaces = new ArrayList<>();
  private TaskRunSpec spec = new TaskRunSpec();
  private RunStatus status;
  private RunPhase phase;
  private String statusMessage;
  private String statusReason;
  private String taskRef;
  private Integer taskVersion;
  private String workflowRef;
  private String workflowRevisionRef;
  private String workflowRunRef;
  private String workflowName;
  private String parentRef;
  private Integer index;

  /** Whether this is an order to run the task. */
  @JsonIgnore
  public boolean isRunOrder() {
    return RunPhase.queued.equals(phase) && RunStatus.ready.equals(status);
  }

  /** Whether this is an order to stop any work held for the task, and report nothing. */
  @JsonIgnore
  public boolean isTerminateOrder() {
    return RunPhase.completed.equals(phase)
        && (RunStatus.cancelled.equals(status) || RunStatus.timedout.equals(status));
  }

  /** The value of the named param, or null when the task run carries none. */
  public Object param(String name) {
    return (params == null)
        ? null
        : params.stream()
            .filter(param -> name.equals(param.getName()))
            .map(RunParam::getValue)
            .findFirst()
            .orElse(null);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public TaskType getType() {
    return type;
  }

  public void setType(TaskType type) {
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Map<String, String> getLabels() {
    return labels;
  }

  public void setLabels(Map<String, String> labels) {
    this.labels = labels;
  }

  public Map<String, Object> getAnnotations() {
    return annotations;
  }

  public void setAnnotations(Map<String, Object> annotations) {
    this.annotations = annotations;
  }

  public Instant getCreationDate() {
    return creationDate;
  }

  public void setCreationDate(Instant creationDate) {
    this.creationDate = creationDate;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public void setStartTime(Instant startTime) {
    this.startTime = startTime;
  }

  public Long getDuration() {
    return duration;
  }

  public void setDuration(Long duration) {
    this.duration = duration;
  }

  /** The timeout in minutes the engine settled for this task; null or 0 is unguarded. */
  public Long getTimeout() {
    return timeout;
  }

  public void setTimeout(Long timeout) {
    this.timeout = timeout;
  }

  public List<RunParam> getParams() {
    return params;
  }

  public void setParams(List<RunParam> params) {
    this.params = params;
  }

  /** The results the task declares; their values arrive on the end report. */
  public List<RunResult> getResults() {
    return results;
  }

  public void setResults(List<RunResult> results) {
    this.results = results;
  }

  public List<TaskWorkspace> getWorkspaces() {
    return workspaces;
  }

  public void setWorkspaces(List<TaskWorkspace> workspaces) {
    this.workspaces = workspaces;
  }

  public TaskRunSpec getSpec() {
    return spec;
  }

  public void setSpec(TaskRunSpec spec) {
    this.spec = spec;
  }

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
  }

  public RunPhase getPhase() {
    return phase;
  }

  public void setPhase(RunPhase phase) {
    this.phase = phase;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  public String getStatusReason() {
    return statusReason;
  }

  public void setStatusReason(String statusReason) {
    this.statusReason = statusReason;
  }

  public String getTaskRef() {
    return taskRef;
  }

  public void setTaskRef(String taskRef) {
    this.taskRef = taskRef;
  }

  public Integer getTaskVersion() {
    return taskVersion;
  }

  public void setTaskVersion(Integer taskVersion) {
    this.taskVersion = taskVersion;
  }

  public String getWorkflowRef() {
    return workflowRef;
  }

  public void setWorkflowRef(String workflowRef) {
    this.workflowRef = workflowRef;
  }

  public String getWorkflowRevisionRef() {
    return workflowRevisionRef;
  }

  public void setWorkflowRevisionRef(String workflowRevisionRef) {
    this.workflowRevisionRef = workflowRevisionRef;
  }

  public String getWorkflowRunRef() {
    return workflowRunRef;
  }

  public void setWorkflowRunRef(String workflowRunRef) {
    this.workflowRunRef = workflowRunRef;
  }

  public String getWorkflowName() {
    return workflowName;
  }

  public void setWorkflowName(String workflowName) {
    this.workflowName = workflowName;
  }

  /** On an item of a for-each task, the parent task run's id. */
  public String getParentRef() {
    return parentRef;
  }

  public void setParentRef(String parentRef) {
    this.parentRef = parentRef;
  }

  /** On an item of a for-each task, its position from 0. */
  public Integer getIndex() {
    return index;
  }

  public void setIndex(Integer index) {
    this.index = index;
  }

  // Params are left out: a param may carry a password.
  @Override
  public String toString() {
    return "TaskRun[id="
        + id
        + ", type="
        + type
        + ", name="
        + name
        + ", phase="
        + phase
        + ", status="
        + status
        + ", workflowRunRef="
        + workflowRunRef
        + "]";
  }
}
