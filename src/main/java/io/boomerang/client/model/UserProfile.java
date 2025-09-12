
package io.boomerang.client.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.boomerang.mongo.model.UserType;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserProfile {

  @JsonProperty("id")
  private String id;
  @JsonProperty("email")
  private String email;
  @JsonProperty("name")
  private String name;
  @JsonProperty("type")
  private UserType type;
  @JsonProperty("status")
  private String status;
  @JsonProperty("teams")
  private List<Team> teams = null;

  @JsonProperty("id")
  public String getId() {
    return id;
  }

  @JsonProperty("id")
  public void setId(String id) {
    this.id = id;
  }

  @JsonProperty("email")
  public String getEmail() {
    return email;
  }

  @JsonProperty("email")
  public void setEmail(String email) {
    this.email = email;
  }

  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  @JsonProperty("type")
  public UserType getType() {
    return type;
  }

  @JsonProperty("type")
  public void setType(UserType type) {
    this.type = type;
  }

  @JsonProperty("status")
  public String getStatus() {
    return status;
  }

  @JsonProperty("status")
  public void setStatus(String status) {
    this.status = status;
  }

  @JsonProperty("teams")
  public List<Team> getTeams() {
    return teams;
  }

  @JsonProperty("teams")
  public void setTeams(List<Team> teams) {
    this.teams = teams;
  }
}