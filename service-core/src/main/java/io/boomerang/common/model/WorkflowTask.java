package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.boomerang.common.enums.TaskType;
import io.swagger.v3.oas.annotations.media.Schema;
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
  // The pinned version, or none to follow the task's latest, which each run reads when it starts.
  // On the wire it is the number or "latest" (versionOnWire, requestVersion).
  @JsonIgnore private Integer taskVersion;
  // Wire only, never stored: the request named no version, so the save pins the current one.
  @JsonIgnore @Transient private boolean pinOnSave;
  // Wire only, never stored: a version that is neither a number nor "latest", refused on save.
  @JsonIgnore @Transient private Object invalidTaskVersion;
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

  /** The wire form of a {@code taskVersion} that follows the task's latest version. */
  public static final String LATEST_VERSION = "latest";

  /** Read from a request: a node that names no version is pinned on save, as before. */
  @JsonCreator
  static WorkflowTask fromRequest() {
    WorkflowTask task = new WorkflowTask();
    task.pinOnSave = true;
    return task;
  }

  /** Return the version as the API shows it: the pinned number, or "latest" for a task node with none. */
  @JsonProperty("taskVersion")
  @Schema(
      oneOf = {Integer.class, String.class},
      description =
          "The pinned task version, or \"latest\" to run the version that is latest when each run starts."
              + " Left out on save, the node is pinned to the current version.")
  public Object versionOnWire() {
    return (taskVersion != null
        ? taskVersion
        : (taskRef != null && !pinOnSave && invalidTaskVersion == null ? LATEST_VERSION : null));
  }

  /**
   * Take the version as a request names it: a number pins it, "latest" follows the latest, and none
   * pins the current version on save. Anything else is kept for the save to refuse.
   */
  @JsonProperty("taskVersion")
  public void requestVersion(Object value) {
    taskVersion = null;
    pinOnSave = false;
    invalidTaskVersion = null;
    if (value == null) {
      pinOnSave = true;
    } else if (value instanceof Number number && number.doubleValue() == number.intValue()) {
      taskVersion = number.intValue();
    } else if (value instanceof String text && text.trim().matches("\\d{1,9}")) {
      taskVersion = Integer.valueOf(text.trim());
    } else if (!(value instanceof String text && LATEST_VERSION.equalsIgnoreCase(text.trim()))) {
      invalidTaskVersion = value;
    }
  }

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
