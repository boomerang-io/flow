package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Report how a task ended. {@code status} is succeeded, failed or invalid; {@code statusReason}
 * types a failure ({@code ExceededQuota} and {@code StartTimeout} mean the task never started, and
 * the engine requeues it); {@code dispatcherRef} is the registered dispatcher id, fenced against
 * the claim. Fields left null are not sent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRunEndRequest {

  private RunStatus status;
  private Map<String, String> labels;
  private Map<String, Object> annotations;
  private List<RunResult> results = new ArrayList<>();
  private String statusMessage;
  private String statusReason;
  private String dispatcherRef;

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
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

  public List<RunResult> getResults() {
    return results;
  }

  public void setResults(List<RunResult> results) {
    this.results = results;
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

  public String getDispatcherRef() {
    return dispatcherRef;
  }

  public void setDispatcherRef(String dispatcherRef) {
    this.dispatcherRef = dispatcherRef;
  }

  @Override
  public String toString() {
    return "TaskRunEndRequest[status="
        + status
        + ", statusReason="
        + statusReason
        + ", statusMessage="
        + statusMessage
        + ", results="
        + (results == null ? 0 : results.size())
        + "]";
  }
}
