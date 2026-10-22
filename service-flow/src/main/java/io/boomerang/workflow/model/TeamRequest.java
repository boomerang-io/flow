package io.boomerang.workflow.model;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.workflow.model.Quotas;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public class TeamRequest {

  private String id;
  private String name;
  private String displayName;
  private TeamStatus status;
  private TeamType type;
  private String externalRef;
  private Map<String, String> labels = new HashMap<>();
  private List<AbstractParam> parameters = new LinkedList<>();
  private Quotas quotas;
  private List<TeamMember> members;
  private List<ApproverGroupRequest> approverGroups;

  @Override
  public String toString() {
    return "TeamRequest [id="
        + id
        + ", name="
        + name
        + ", displayName="
        + displayName
        + ", status="
        + status
        + ", type="
        + type
        + ", externalRef="
        + externalRef
        + ", labels="
        + labels
        + ", parameters="
        + parameters
        + ", quotas="
        + quotas
        + ", members="
        + members
        + ", approverGroups="
        + approverGroups
        + "]";
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

  public TeamStatus getStatus() {
    return status;
  }

  public void setStatus(TeamStatus status) {
    this.status = status;
  }

  public TeamType getType() {
    return type;
  }

  public void setType(TeamType type) {
    this.type = type;
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

  public Quotas getQuotas() {
    return quotas;
  }

  public void setQuotas(Quotas quotas) {
    this.quotas = quotas;
  }

  public List<TeamMember> getMembers() {
    return members;
  }

  public void setMembers(List<TeamMember> members) {
    this.members = members;
  }

  public List<ApproverGroupRequest> getApproverGroups() {
    return approverGroups;
  }

  public void setApproverGroups(List<ApproverGroupRequest> approverGroups) {
    this.approverGroups = approverGroups;
  }
}
