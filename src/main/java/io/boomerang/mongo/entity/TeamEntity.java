package io.boomerang.mongo.entity;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.mongo.model.ApproverGroup;
import io.boomerang.mongo.model.KeyValuePair;
import io.boomerang.mongo.model.Quotas;
import io.boomerang.mongo.model.Settings;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('teams')}")
public class TeamEntity {

  private String higherLevelGroupId;

  @Id
  private String id;
  private Boolean isActive;

  @Pattern(
        regexp = "^[a-zA-Z0-9 _.-]+$",
        message = "Name can only contain letters, numbers, spaces, dashes (-), underscores (_), and periods (.)"
    )
  private String name;

  private Settings settings;

  private Quotas quotas;
  private List<ApproverGroup> approverGroups;

  private List<KeyValuePair> labels;
  
  private List<String> userRoles;

  public String getHigherLevelGroupId() {
    return higherLevelGroupId;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setHigherLevelGroupId(String higherLevelGroupId) {
    this.higherLevelGroupId = higherLevelGroupId;
  }

  public void setId(String id) {
    this.id = id;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Boolean getIsActive() {
    return isActive;
  }

  public void setIsActive(Boolean isActive) {
    this.isActive = isActive;
  }

  public Settings getSettings() {
    return settings;
  }

  public void setSettings(Settings settings) {
    this.settings = settings;
  }

  public Quotas getQuotas() {
    return quotas;
  }

  public void setQuotas(Quotas quotas) {
    this.quotas = quotas;
  }

  public List<ApproverGroup> getApproverGroups() {
    return approverGroups;
  }

  public void setApproverGroups(List<ApproverGroup> approverGroups) {
    this.approverGroups = approverGroups;
  }

  public List<KeyValuePair> getLabels() {
    return labels;
  }

  public void setLabels(List<KeyValuePair> labels) {
    this.labels = labels;
  }

  public List<String> getUserRoles() {
    return userRoles;
  }

  public void setUserRoles(List<String> userRoles) {
	this.userRoles = userRoles;
  }

}
