package io.boomerang.workspace.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.BeanUtils;
import io.boomerang.workspace.entity.WorkspaceEntity;

public class WorkspaceSummary {

  private String name;
  private String displayName;
  private Date creationDate = new Date();
  private WorkspaceStatus status = WorkspaceStatus.active;
  private String externalRef;
  private Map<String, String> labels = new HashMap<>();
  private WorkspaceSummaryInsights insights;
  
  public WorkspaceSummary() {
    
  }
  
  public WorkspaceSummary(Workspace entity) {
    BeanUtils.copyProperties(entity, this);
  }

  
  public WorkspaceSummary(WorkspaceEntity entity) {
    BeanUtils.copyProperties(entity, this);
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

  public Map<String, String> getLabels() {
    return labels;
  }

  public void setLabels(Map<String, String> labels) {
    this.labels = labels;
  }

  public WorkspaceSummaryInsights getInsights() {
    return insights;
  }

  public void setInsights(WorkspaceSummaryInsights insights) {
    this.insights = insights;
  }
}
