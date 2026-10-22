package io.boomerang.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class HeaderFeatures {

  @JsonProperty("notifications.enabled")
  private Boolean notificationsEnabled;

  @JsonProperty("support.enabled")
  private Boolean supportEnabled;

  @JsonProperty("docs.enabled")
  private Boolean docsEnabled;

  @JsonProperty("internal.enabled")
  private Boolean internalEnabled;

  @JsonProperty("consent.enabled")
  private Boolean consentEnabled;
}
