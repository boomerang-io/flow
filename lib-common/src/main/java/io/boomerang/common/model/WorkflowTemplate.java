package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.boomerang.common.entity.WorkflowTemplateEntity;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;

/*
 * Public API model based on WorkflowTemplateEntity.
 *
 * Standalone POJO (no longer extends the entity) so the public contract is explicit. Mirrors
 * the entity's own @JsonIgnore on id (never serialized); every other entity field stays here.
 * upgradesAvailable is a model-only addition (computed at apply-time, not persisted on the
 * entity).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
  "id",
  "name",
  "displayName",
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
public class WorkflowTemplate {

  @Id @JsonIgnore private String id;
  private String name;
  private String displayName;
  private Date creationDate = new Date();
  private Integer version;
  private String icon;
  private String description;
  private String markdown;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private List<WorkflowTask> tasks = new LinkedList<>();
  private ChangeLog changelog;
  private List<AbstractParam> params;
  private List<WorkflowWorkspace> workspaces;
  private Long timeout;
  private Long retries;

  private boolean upgradesAvailable = false;

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

  public WorkflowTemplate() {}

  /*
   * Creates a WorkflowTemplate from WorkflowTemplateEntity
   */
  public WorkflowTemplate(WorkflowTemplateEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }

  public boolean isUpgradesAvailable() {
    return upgradesAvailable;
  }

  public void setUpgradesAvailable(boolean upgradesAvailable) {
    this.upgradesAvailable = upgradesAvailable;
  }
}
