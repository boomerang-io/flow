package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.boomerang.common.enums.TaskDeletion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.ToString;

@Data
public class TaskRunSpec {

  // Arguments, command and script are filled in at claim, where a secret reference becomes its
  // value, so toString leaves them out; envs print their names only (TaskEnvVar).
  @ToString.Exclude private List<String> arguments;
  @ToString.Exclude private List<String> command;
  private List<TaskEnvVar> envs;
  private String image;
  @ToString.Exclude private String script;
  private String workingDir;
  private Boolean debug = false;
  private int timeout;
  private TaskDeletion deletion;
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
