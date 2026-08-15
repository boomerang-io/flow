package io.boomerang.workspace.entity;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.boomerang.common.model.AbstractParam;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceStatus;
import io.boomerang.workspace.model.WorkspaceType;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('teams')}")
public class WorkspaceEntity {

  @Id private String id;
  private String name;
  private String displayName;
  private Date creationDate = new Date();
  private WorkspaceType type;
  private WorkspaceStatus status = WorkspaceStatus.active;
  private String externalRef;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private List<AbstractParam> parameters = new LinkedList<>();
  //  private WorkspaceSettings settings;
  private Quotas quotas;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public Date getCreationDate() {
    return creationDate;
  }

  public void setCreationDate(Date creationDate) {
    this.creationDate = creationDate;
  }

  public WorkspaceType getType() {
    return type;
  }

  public void setType(WorkspaceType type) {
    this.type = type;
  }

  public WorkspaceStatus getStatus() {
    return status;
  }

  public void setStatus(WorkspaceStatus status) {
    this.status = status;
  }

  public String getExternalRef() {
    return externalRef;
  }

  public void setExternalRef(String externalRef) {
    this.externalRef = externalRef;
  }

  //  public WorkspaceSettings getSettings() {
  //    return settings;
  //  }
  //  public void setSettings(WorkspaceSettings settings) {
  //    this.settings = settings;
  //  }
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

  public List<AbstractParam> getParameters() {
    return parameters;
  }

  public void setParameters(List<AbstractParam> parameters) {
    this.parameters = parameters;
  }

  public Quotas getQuotas() {
    return quotas;
  }

  public void setQuotas(Quotas quotas) {
    this.quotas = quotas;
  }
}
