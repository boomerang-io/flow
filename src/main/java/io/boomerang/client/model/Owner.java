
package io.boomerang.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Owner {

  @JsonProperty("ownerId")
  private String ownerId;
  @JsonProperty("ownerEmail")
  private String ownerEmail;
  @JsonProperty("ownerName")
  private String ownerName;

  @JsonProperty("ownerId")
  public String getOwnerId() {
    return ownerId;
  }

  @JsonProperty("ownerId")
  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  @JsonProperty("ownerEmail")
  public String getOwnerEmail() {
    return ownerEmail;
  }

  @JsonProperty("ownerEmail")
  public void setOwnerEmail(String ownerEmail) {
    this.ownerEmail = ownerEmail;
  }

  @JsonProperty("ownerName")
  public String getOwnerName() {
    return ownerName;
  }

  @JsonProperty("ownerName")
  public void setOwnerName(String ownerName) {
    this.ownerName = ownerName;
  }

}
