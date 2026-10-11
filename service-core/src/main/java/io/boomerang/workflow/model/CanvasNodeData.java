package io.boomerang.workflow.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.WorkflowTaskForeach;

public class CanvasNodeData {
  
  String name;
  List<RunParam> params;
  List<ResultSpec> results;
  String taskRef;
  Integer taskVersion;
  boolean upgradesAvailable;
  WorkflowTaskForeach foreach;
  
  @JsonIgnore
  private Map<String, Object> additionalProperties = new HashMap<>();
  
  public String getName() {
    return name;
  }

  public void setName(String label) {
    this.name = label;
  }

  public List<RunParam> getParams() {
    return params;
  }

  public void setParams(List<RunParam> params) {
    this.params = params;
  }
  
  public List<ResultSpec> getResults() {
    return results;
  }

  public void setResults(List<ResultSpec> results) {
    this.results = results;
  }

  public String getTaskRef() {
    return taskRef;
  }

  public void setTaskRef(String templateRef) {
    this.taskRef = templateRef;
  }
  
  public Integer getTaskVersion() {
    return taskVersion;
  }

  public void setTaskVersion(Integer taskVersion) {
    this.taskVersion = taskVersion;
  }

  public boolean getUpgradesAvailable() {
    return upgradesAvailable;
  }

  public void setUpgradesAvailable(boolean upgradesAvailable) {
    this.upgradesAvailable = upgradesAvailable;
  }

  public WorkflowTaskForeach getForeach() {
    return foreach;
  }

  public void setForeach(WorkflowTaskForeach foreach) {
    this.foreach = foreach;
  }

  @JsonAnyGetter
  public Map<String, Object> getAdditionalProperties() {
    return this.additionalProperties;
  }

  @JsonAnySetter
  public void setAdditionalProperty(String name, Object value) {
    this.additionalProperties.put(name, value);
  }
}
