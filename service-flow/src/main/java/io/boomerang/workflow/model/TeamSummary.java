package io.boomerang.workflow.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.BeanUtils;
import io.boomerang.workflow.entity.TeamEntity;

public class TeamSummary {

  private String name;
  private String displayName;
  private Date creationDate = new Date();
  private TeamStatus status = TeamStatus.active;
  private String externalRef;
  private Map<String, String> labels = new HashMap<>();
  private TeamSummaryInsights insights;
  
  public TeamSummary() {
    
  }
  
  public TeamSummary(Team entity) {
    BeanUtils.copyProperties(entity, this);
  }

  
  public TeamSummary(TeamEntity entity) {
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

  public TeamStatus getStatus() {
    return status;
  }

  public void setStatus(TeamStatus status) {
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

  public TeamSummaryInsights getInsights() {
    return insights;
  }

  public void setInsights(TeamSummaryInsights insights) {
    this.insights = insights;
  }
}
