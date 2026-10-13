package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow run as the workflow queue hands it out: one that declares workspaces, claimed so the
 * dispatcher can provision their storage before its tasks run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowRun {

  private String id;
  private Instant creationDate;
  private RunStatus status;
  private RunPhase phase;
  private Boolean paused;
  private Instant startTime;
  private Long duration;
  private String statusMessage;
  private Long timeout;
  private Long retries;
  private String workflowRef;
  private String workflowRevisionRef;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private List<RunParam> params = new ArrayList<>();
  private List<TaskRun> tasks;
  private Boolean debug;
  private String workflowName;
  private String workflowDisplayName;
  private Integer workflowVersion;
  private String trigger;
  private String initiatedByRef;
  private String initiatedByWorkflowRunRef;
  private List<RunResult> results = new ArrayList<>();
  private List<WorkflowWorkspace> workspaces = new ArrayList<>();
  private Boolean awaitingApproval;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Instant getCreationDate() {
    return creationDate;
  }

  public void setCreationDate(Instant creationDate) {
    this.creationDate = creationDate;
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

  public Boolean getPaused() {
    return paused;
  }

  public void setPaused(Boolean paused) {
    this.paused = paused;
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

  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  public Long getTimeout() {
    return timeout;
  }

  public void setTimeout(Long timeout) {
    this.timeout = timeout;
  }

  public Long getRetries() {
    return retries;
  }

  public void setRetries(Long retries) {
    this.retries = retries;
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

  public List<RunParam> getParams() {
    return params;
  }

  public void setParams(List<RunParam> params) {
    this.params = params;
  }

  public List<TaskRun> getTasks() {
    return tasks;
  }

  public void setTasks(List<TaskRun> tasks) {
    this.tasks = tasks;
  }

  public Boolean getDebug() {
    return debug;
  }

  public void setDebug(Boolean debug) {
    this.debug = debug;
  }

  public String getWorkflowName() {
    return workflowName;
  }

  public void setWorkflowName(String workflowName) {
    this.workflowName = workflowName;
  }

  public String getWorkflowDisplayName() {
    return workflowDisplayName;
  }

  public void setWorkflowDisplayName(String workflowDisplayName) {
    this.workflowDisplayName = workflowDisplayName;
  }

  public Integer getWorkflowVersion() {
    return workflowVersion;
  }

  public void setWorkflowVersion(Integer workflowVersion) {
    this.workflowVersion = workflowVersion;
  }

  public String getTrigger() {
    return trigger;
  }

  public void setTrigger(String trigger) {
    this.trigger = trigger;
  }

  public String getInitiatedByRef() {
    return initiatedByRef;
  }

  public void setInitiatedByRef(String initiatedByRef) {
    this.initiatedByRef = initiatedByRef;
  }

  public String getInitiatedByWorkflowRunRef() {
    return initiatedByWorkflowRunRef;
  }

  public void setInitiatedByWorkflowRunRef(String initiatedByWorkflowRunRef) {
    this.initiatedByWorkflowRunRef = initiatedByWorkflowRunRef;
  }

  public List<RunResult> getResults() {
    return results;
  }

  public void setResults(List<RunResult> results) {
    this.results = results;
  }

  public List<WorkflowWorkspace> getWorkspaces() {
    return workspaces;
  }

  public void setWorkspaces(List<WorkflowWorkspace> workspaces) {
    this.workspaces = workspaces;
  }

  public Boolean getAwaitingApproval() {
    return awaitingApproval;
  }

  public void setAwaitingApproval(Boolean awaitingApproval) {
    this.awaitingApproval = awaitingApproval;
  }

  // Params are left out: a param may carry a password.
  @Override
  public String toString() {
    return "WorkflowRun[id="
        + id
        + ", workflowRef="
        + workflowRef
        + ", phase="
        + phase
        + ", status="
        + status
        + ", workspaces="
        + workspaces
        + "]";
  }
}
