package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Tell the engine a task is starting. {@code dispatcherRef} is the registered dispatcher id; the
 * engine refuses a start from a dispatcher that no longer holds the claim. Fields left null are
 * not sent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRunStartRequest {

  private Map<String, String> labels;
  private Map<String, Object> annotations;
  private List<RunParam> params;
  private Map<String, String> workspaces;
  private Long timeout;
  private Boolean preApproved;
  private String dispatcherRef;

  public TaskRunStartRequest() {}

  public TaskRunStartRequest(String dispatcherRef) {
    this.dispatcherRef = dispatcherRef;
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

  public Map<String, String> getWorkspaces() {
    return workspaces;
  }

  public void setWorkspaces(Map<String, String> workspaces) {
    this.workspaces = workspaces;
  }

  public Long getTimeout() {
    return timeout;
  }

  public void setTimeout(Long timeout) {
    this.timeout = timeout;
  }

  public Boolean getPreApproved() {
    return preApproved;
  }

  public void setPreApproved(Boolean preApproved) {
    this.preApproved = preApproved;
  }

  public String getDispatcherRef() {
    return dispatcherRef;
  }

  public void setDispatcherRef(String dispatcherRef) {
    this.dispatcherRef = dispatcherRef;
  }
}
