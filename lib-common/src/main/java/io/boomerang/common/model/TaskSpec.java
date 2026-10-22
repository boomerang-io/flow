package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class TaskSpec {

  private List<String> arguments;
  private List<String> command;
  private List<AbstractParam> params = new LinkedList<>();
  private List<TaskEnvVar> envs = new LinkedList<>();
  private String image;
  private List<ResultSpec> results = new LinkedList<>();
  private String script;
  private String workingDir;
  @JsonIgnore private Map<String, Object> additionalProperties = new HashMap<>();

  @JsonAnyGetter
  public Map<String, Object> getAdditionalProperties() {
    return this.additionalProperties;
  }

  @JsonAnySetter
  public void setAdditionalProperty(String name, Object value) {
    this.additionalProperties.put(name, value);
  }
}
