package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Who a dispatcher is and which task types it runs. The engine upserts on name and host, so a
 * restarted dispatcher keeps its id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DispatcherRegistrationRequest {

  private String name;
  private Integer version;
  private String host;
  private List<String> workflowAnnotations = new ArrayList<>();
  private List<String> taskAnnotations = new ArrayList<>();
  private List<String> taskTypes = new ArrayList<>();

  public DispatcherRegistrationRequest() {}

  public DispatcherRegistrationRequest(String name, String host, List<String> taskTypes) {
    this.name = name;
    this.host = host;
    this.taskTypes = taskTypes;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public List<String> getWorkflowAnnotations() {
    return workflowAnnotations;
  }

  public void setWorkflowAnnotations(List<String> workflowAnnotations) {
    this.workflowAnnotations = workflowAnnotations;
  }

  public List<String> getTaskAnnotations() {
    return taskAnnotations;
  }

  public void setTaskAnnotations(List<String> taskAnnotations) {
    this.taskAnnotations = taskAnnotations;
  }

  public List<String> getTaskTypes() {
    return taskTypes;
  }

  public void setTaskTypes(List<String> taskTypes) {
    this.taskTypes = taskTypes;
  }
}
