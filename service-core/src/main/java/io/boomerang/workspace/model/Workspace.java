package io.boomerang.workspace.model;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.boomerang.common.model.AbstractParam;
import org.springframework.beans.BeanUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workflow.model.WorkflowSummary;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Workspace {

  private String id;
  private String name;
  private String displayName;
  private Date creationDate = new Date();
  private WorkspaceStatus status = WorkspaceStatus.active;
  private String externalRef;
  private Map<String, String> labels = new HashMap<>();
  private List<AbstractParam> parameters = new LinkedList<>();
  //  private WorkspaceSettings settings;
  private CurrentQuotas quotas;
  private List<WorkspaceMember> members = new LinkedList<>();
  private List<WorkflowSummary> workflows = new LinkedList<>();
  private List<ApproverGroup> approverGroups = new LinkedList<>();

  public Workspace() {}

  public Workspace(WorkspaceEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }

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

  public List<AbstractParam> getParameters() {
    return parameters;
  }

  public void setParameters(List<AbstractParam> parameters) {
    this.parameters = parameters;
  }

  //  public WorkspaceSettings getSettings() {
  //    return settings;
  //  }
  //
  //  public void setSettings(WorkspaceSettings settings) {
  //    this.settings = settings;
  //  }

  public CurrentQuotas getQuotas() {
    return quotas;
  }

  public void setQuotas(CurrentQuotas quotas) {
    this.quotas = quotas;
  }

  public List<WorkflowSummary> getWorkflows() {
    return workflows;
  }

  public void setWorkflows(List<WorkflowSummary> workflows) {
    this.workflows = workflows;
  }

  public List<WorkspaceMember> getMembers() {
    return members;
  }

  public void setMembers(List<WorkspaceMember> members) {
    this.members = members;
  }

  public List<ApproverGroup> getApproverGroups() {
    return approverGroups;
  }

  public void setApproverGroups(List<ApproverGroup> approverGroups) {
    this.approverGroups = approverGroups;
  }
}
