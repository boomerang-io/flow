package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.boomerang.common.enums.WorkflowStatus;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;

/*
 * Workflow Model joining Workflow Entity and Workflow Revision Entity
 *
 * A number of the Workflow Revision elements are put under metadata
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
  "id",
  "name",
  "displayName",
  "status",
  "version",
  "creationDate",
  "timeout",
  "retries",
  "description",
  "labels",
  "annotations",
  "params",
  "tasks"
})
public class Workflow {
  @Id private String id;
  private String name;
  private String displayName;
  private WorkflowStatus status = WorkflowStatus.active;
  private Integer version = 1;
  private Date creationDate = new Date();
  private ChangeLog changelog;
  private String icon;
  private String description;
  private String markdown;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Long timeout;
  private Long retries;
  private boolean upgradesAvailable = false;
  private WorkflowTrigger triggers = new WorkflowTrigger();
  private List<WorkflowTask> tasks = new LinkedList<>();
  private List<AbstractParam> params = new LinkedList<>();
  private List<WorkflowWorkspace> workspaces = new LinkedList<>();
  private Map<String, Object> unknownFields = new HashMap<>();

  @Override
  public String toString() {
    return "Workflow [id="
        + id
        + "name="
        + name
        + ", displayName="
        + displayName
        + ", status="
        + status
        + ", version="
        + version
        + ", creationDate="
        + creationDate
        + ", changelog="
        + changelog
        + ", icon="
        + icon
        + ", description="
        + description
        + ", markdown="
        + markdown
        + ", labels="
        + labels
        + ", annotations="
        + annotations
        + ", timeout="
        + timeout
        + ", retries="
        + retries
        + ", upgradesAvailable="
        + upgradesAvailable
        + ", triggers="
        + triggers
        + ", tasks="
        + tasks
        + ", params="
        + params
        + ", workspaces="
        + workspaces
        + ", unknownFields="
        + unknownFields
        + "]";
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
