
package io.boomerang.client.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Team {

  @JsonProperty("id")
  private String id;
  @JsonProperty("name")
  private String name;
  @JsonProperty("shortName")
  private String shortName;
  @JsonProperty("owners")
  private List<Owner> owners = null;
  @JsonProperty("purpose")
  private String purpose;
  @JsonProperty("dateCreated")
  private String dateCreated;
  @JsonProperty("isActive")
  private Boolean isActive;
  @JsonProperty("newRelicRestApiKey")
  private Object newRelicRestApiKey;
  @JsonProperty("newRelicQueryKey")
  private Object newRelicQueryKey;
  @JsonProperty("newRelicAccountId")
  private Object newRelicAccountId;
  @JsonProperty("autoApproveRequests")
  private Boolean autoApproveRequests;
  @JsonProperty("privateTeam")
  private Boolean privateTeam;
  @JsonProperty("ldapName")
  private String ldapName;

  @JsonProperty("lowLevelGroupId")
  private String lowLevelGroupId;

  @JsonProperty("id")
  public String getId() {
    return id;
  }

  @JsonProperty("id")
  public void setId(String id) {
    this.id = id;
  }

  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  @JsonProperty("shortName")
  public String getShortName() {
    return shortName;
  }

  @JsonProperty("shortName")
  public void setShortName(String shortName) {
    this.shortName = shortName;
  }

  @JsonProperty("owners")
  public List<Owner> getOwners() {
    return owners;
  }

  @JsonProperty("owners")
  public void setOwners(List<Owner> owners) {
    this.owners = owners;
  }

  @JsonProperty("purpose")
  public String getPurpose() {
    return purpose;
  }

  @JsonProperty("purpose")
  public void setPurpose(String purpose) {
    this.purpose = purpose;
  }

  @JsonProperty("dateCreated")
  public String getDateCreated() {
    return dateCreated;
  }

  @JsonProperty("dateCreated")
  public void setDateCreated(String dateCreated) {
    this.dateCreated = dateCreated;
  }

  @JsonProperty("isActive")
  public Boolean getIsActive() {
    return isActive;
  }

  @JsonProperty("isActive")
  public void setIsActive(Boolean isActive) {
    this.isActive = isActive;
  }

  @JsonProperty("newRelicRestApiKey")
  public Object getNewRelicRestApiKey() {
    return newRelicRestApiKey;
  }

  @JsonProperty("newRelicRestApiKey")
  public void setNewRelicRestApiKey(Object newRelicRestApiKey) {
    this.newRelicRestApiKey = newRelicRestApiKey;
  }

  @JsonProperty("newRelicQueryKey")
  public Object getNewRelicQueryKey() {
    return newRelicQueryKey;
  }

  @JsonProperty("newRelicQueryKey")
  public void setNewRelicQueryKey(Object newRelicQueryKey) {
    this.newRelicQueryKey = newRelicQueryKey;
  }

  @JsonProperty("newRelicAccountId")
  public Object getNewRelicAccountId() {
    return newRelicAccountId;
  }

  @JsonProperty("newRelicAccountId")
  public void setNewRelicAccountId(Object newRelicAccountId) {
    this.newRelicAccountId = newRelicAccountId;
  }

  @JsonProperty("autoApproveRequests")
  public Boolean getAutoApproveRequests() {
    return autoApproveRequests;
  }

  @JsonProperty("autoApproveRequests")
  public void setAutoApproveRequests(Boolean autoApproveRequests) {
    this.autoApproveRequests = autoApproveRequests;
  }

  @JsonProperty("privateTeam")
  public Boolean getPrivateTeam() {
    return privateTeam;
  }

  @JsonProperty("privateTeam")
  public void setPrivateTeam(Boolean privateTeam) {
    this.privateTeam = privateTeam;
  }

  @JsonProperty("ldapName")
  public String getLdapName() {
    return ldapName;
  }

  @JsonProperty("ldapName")
  public void setLdapName(String ldapName) {
    this.ldapName = ldapName;
  }

  @JsonProperty("lowLevelGroupId")
  public String getLowLevelGroupId() {
    return lowLevelGroupId;
  }

  @JsonProperty("lowLevelGroupId")
  public void setLowLevelGroupId(String lowLevelGroupId) {
    this.lowLevelGroupId = lowLevelGroupId;
  }

}
