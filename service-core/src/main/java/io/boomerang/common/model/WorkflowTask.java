package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.boomerang.common.enums.TaskType;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Transient;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
  "name",
  "type",
  "templateRef",
  "templateVersion",
  "templateUpgradesAvailable",
  "timeout",
  "retry",
  "dependencies"
})
public class WorkflowTask {

  private String name;
  private TaskType type;
  private String taskRef;
  private Integer taskVersion;
  @JsonInclude() @Transient private Boolean upgradesAvailable = false;
  private Long timeout;
  // Optional - retry a failure the dispatcher reports: how many times and after what backoff.
  private WorkflowTaskRetry retry;
  private List<WorkflowTaskDependency> dependencies = new LinkedList<>();
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  // Uses RunParam as the ParamSpec comes from the TaskTemplate
  private List<RunParam> params = new LinkedList<>();
  // This is needed as some of our Tasks allow you to define Result Definitions on the fly
  private List<ResultSpec> results = new LinkedList<>();
  // Optional - absent or true mounts the run's workspace, false opts this task out
  // Not supported by all integrations
  private Boolean workspace;
  // Optional - run this task once per item of a list. Absent runs the task once.
  private WorkflowTaskForeach foreach;
  private Map<String, Object> unknownFields = new HashMap<>();

  @JsonAnyGetter
  @JsonPropertyOrder(alphabetic = true)
  public Map<String, Object> otherFields() {
    return unknownFields;
  }

  @JsonAnySetter
  public void setOtherField(String name, Object value) {
    unknownFields.put(name, value);
  }
}
